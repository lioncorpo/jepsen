(ns jepsen.tests.monotonic-cycle
  "A checker which searches for incidents of read skew. Because each register
  is increment-only, we know that there should never exist a pair of reads r1
  and r2, such that for two registers x and y, where both registers are
  observed by both reads, x_r1 < x_r2 and y_r1 > y_r2.

  This problem is equivalent to cycle detection: we have a set of partial
  orders <x, <y, ..., each of which relates states based on whether x increases
  or not. We're trying to determine whether these orders(not ) are *compatible*.

  Imagine an order <x as a graph over states, and likewise for <y, <z, etc.
  Take the union of these graphs. If all these orders are compatible, there
  should be no cycles in this graph.

  To do this, we take each key k, and find all values for k. In general, the
  ordering relation <k is the transitive closure, but for cycle detection, we
  don't actually need the full closure--we'll restrict ourselves to k=1's
  successors being those with k=2 (or, if there are no k=2, use k=3, etc). This
  gives us a set of directed edges over states for k; we union the graphs for
  all k together to obtain a graph of all relationships.

  Next, we apply Tarjan's algorithm for strongly connected components, which is
  linear in edges + vertices (which is why we don't work with the full
  transitive closure of <k). The existence of any strongly connected components
  containing more than one vertex implies a cycle in the graph, and that cycle
  will be within that component.

  This isn't suuuper ideal... the connected component could, I guess, be fairly
  large, and then it'd be hard to prove where the cycle lies. But this feels
  like an OK start."
  (:require [jepsen.checker :as checker]
            [jepsen.util :as util]
            [knossos.op :as op]
            [clojure.tools.logging :refer [info error warn]]
            [clojure.core.reducers :as r]
            [clojure.set :as set]
            [jepsen.generator :as gen]
            [fipp.edn :refer [pprint]]))

(set! *warn-on-reflection* true)

;; TODO Remove later, just keeping it around to compare results
(defn recur-tarjan
  "Returns the strongly connected components of a graph specified by its nodes
   and a successor function succs from node to nodes.
   The used algorithm is Tarjan's one."
  [succs]
  (let [nodes (keys succs)]
    (letfn [(sc [env node]
              ;; env is a map from nodes to stack length or nil,
              ;; nil means the node is known to belong to another SCC
              ;; there are two special keys: ::stack for the current stack
              ;; and ::sccs for the current set of SCCs
              (if (contains? env node)
                env
                (let [stack (::stack env)
                      n (count stack)
                      env (assoc env node n ::stack (conj stack node))
                      env (reduce (fn [env succ]
                                    (let [env (sc env succ)]
                                      (assoc env node (min (or (env succ) n) (env node)))))
                                  env (succs node))]
                  (if (= n (env node)) ; no link below us in the stack, call it a SCC
                    (let [nodes (::stack env)
                          scc (set (take (- (count nodes) n) nodes))
                          ;; clear all stack lengths for these nodes since this SCC is done
                          env (reduce #(assoc %1 %2 nil) env scc)]
                      (assoc env ::stack stack ::sccs (conj (::sccs env) scc)))
                    env))))]
      (::sccs (reduce sc {::stack () ::sccs #{}} nodes)))))

(defn tarjan
  "Returns the strongly connected components of a graph specified by its
  nodes (ints) and a successor function (succs node) from node to nodes.
  A iterative verison of Tarjan's Strongly Connected Components."
  [succs]
  (let [^clojure.lang.APersistentMap$KeySeq
        nodes (keys succs)
        ;; Ensure there's a table index for every value we could find
        table-size (->> nodes (apply max) inc)
        result (loop [index      0
                      prev-node  nil
                      low        (int-array table-size)
                      visited    (boolean-array table-size)
                      ^clojure.lang.PersistentHashMap$ArrayNode$Iter
                      iterator   (.iterator nodes)
                      iter-stack '()
                      min-stack  '()
                      stack      '()
                      sccs       #{}]
                 (pprint {:index index :iter-stack (count iter-stack)
                          :min-stack min-stack :stack stack :sccs sccs})
                 ;; Depth-first search
                 (if-let [node (when (.hasNext iterator)
                                 (.next iterator))]
                   ;; Has the node been visited?
                   (if-not (aget visited node)
                     ;; New node! Go down a level
                     (let [_          (aset low node index)
                           _          (aset visited node true)
                           stack      (conj stack node)
                           m          (aget low node)
                           min-stack  (conj min-stack m)
                           iter-stack (conj iter-stack iterator)
                           ^clojure.lang.PersistentHashSet
                           edges      (succs node)
                           iterator   (.iterator edges)
                           index      (inc index)]
                       (recur index node low visited iterator
                              iter-stack min-stack stack sccs))

                     ;; We've seen this before, update the min stack
                     (let [min-stack (if (< 0 (count min-stack)) ; Ensure we have a recent minimum
                                       (let [;; We do, let's grab it
                                             m         (peek min-stack)
                                             min-stack (pop min-stack)
                                             ;; Compare the recent lowest to node's current index
                                             i' (aget low prev-node)
                                             ;; Find the smaller of the node's index or last min
                                             low   (min i' m)]
                                         (conj min-stack low))
                                       min-stack)]
                       (recur index node low visited iterator
                              iter-stack min-stack stack sccs)))

                   ;; Done checking this set of edges
                   ;; Are we done?
                   (if (zero? (count iter-stack))
                     ;; No more large components, let's drain the non-components
                     ;; nodes at the end
                     sccs
                     ;; More to search
                     (let [iterator   (peek iter-stack)
                           iter-stack (pop iter-stack)

                           ^Integer m (peek min-stack)
                           min-stack  (pop min-stack)

                           ;; Should we start a component?
                           [stack scc] (if (< m (aget low prev-node))
                                         (do
                                           (aset low prev-node m)
                                           [stack #{}])

                                         ;; SCC good to go sir
                                         (loop [w     (or (peek stack) prev-node)
                                                stack (try
                                                        (pop stack)
                                                        (catch java.lang.IllegalStateException _
                                                          '()))
                                                scc   #{}]
                                           (pprint {:w w :prev-node prev-node :scc scc})
                                           (if (not= prev-node w)
                                             (let [scc (conj scc w)
                                                   _   (aset low w (count nodes))
                                                   w'  (or (peek stack) prev-node)
                                                   stack (try
                                                           (pop stack)
                                                           (catch java.lang.IllegalStateException _
                                                             '()))]
                                               (recur w' stack scc))
                                             [stack (conj scc w)])))

                           ;; Add SCC to collection if we have one
                           sccs (if (< 0 (count scc))
                                  (conj sccs scc)
                                  sccs)

                           ;; Update head of min-stack
                           min-stack (if (< 0 (count min-stack))
                                       (let [m         (peek min-stack)
                                             min-stack (pop min-stack)

                                             i' (aget low prev-node)
                                             ;; Find the smaller of the node's index or last min
                                             low       (min i' m)
                                             min-stack (conj min-stack low)]
                                         min-stack)
                                       min-stack)]
                       (recur index prev-node low visited iterator
                              iter-stack min-stack stack sccs)))))]
    result))

(defn merge-merge-union
  "Return a partial function which merges and unions maps containing maps
  of sets. Ex.
  {:x {0 #{1 2 3}}}
  {:x {0 #{4 5 6}}}
  {:x {1 #{7 8 9}}}
  =>
  {:x {0 #{1 2 3 4 5 6}
       1 #{7 8 9}}}"
  []
  (partial merge-with (partial merge-with clojure.set/union)))

(defn key-index
  "Takes an empty map or key-index map and an op, merging the op into the
  key-index. A key-index is a per-key map of values to the transactions that
  have observed that value.
  Ex:
  (key-index {:x {0 #{1}}}, {:index 4 :f :read :value {:x 3 :y 7}})
  => {:x {0 #{1}
          3 #{4}}
      :y {7 #{4}}}"
  ([values {:keys [index value]}]
   (let [indices (map (fn [[k v]] {k (sorted-map v #{index})})
                      value)]
     (apply (merge-merge-union) values indices))))

(defn key-orders
  "Takes an key-index, an empty map or key-order map, and an op representing
  a read transaction. Looks up the transactions that succeed this one, on the
  key-index and merges the txn and its succs into the key-order.
  Ex:
  (key-orders {:x {0 #{1} 1 #{2}}}, {} {:index 3 :f :read :value {:x 3}})
  => {:x {0 #{1}
          1 #{2}
          2 #{3}}}"
  [key-index orders {:keys [index value]}]
  (let [succs (map (fn [[k v]]
                     (let [idx (key-index k)]
                       (loop [v' (inc v)]
                         (let [succs (idx v')]
                           (cond
                             ;; No more succs, we're done with the graph
                             (< (count idx) (dec v')) {k {index #{}}}
                             ;; Found succs
                             (<= 1 (count succs)) {k {index succs}}
                             ;; Missing vals, keep incrementing
                             :else (recur (inc v')))))))
                   value)]
    (apply (merge-merge-union) orders succs)))

(defn precedence-graph
  "Takes a history of read txns and returns a single precedence graph of the
  transactions across all keys."
  [history]
  (let [idx        (r/reduce key-index {} history)
        key-orders (r/reduce (partial key-orders idx) {} history)]

    ;; Merge all of our key-orders together into a precedence graph
    (->> key-orders
         vals
         (apply merge-with clojure.set/union))))

(defn checker []
  (reify checker/Checker
    (check [this test history opts]
      (let [h          (->> history
                            (filter op/ok?)
                            (filter #(= :read (:f %))))
            g          (precedence-graph h)
            components (tarjan g)
            errors     (filter #(< 1 (count %)) components)]

        ;; Auto-validate single-key histories
        {:valid? (empty? errors)
         :errors errors}))))

(defn w-inc [ks]
  {:f :inc, :type :invoke, :value (vec ks)})

(defn r [ks]
  (let [v (->> ks
               (map (fn [k] [k nil]))
               (into {}))]
    {:f :read, :type :invoke, :value v}))

(defn workload
  "A package of a generator and checker. Options:

    :keys   A set of registers you're going to operate on. Allows us to generate
            monotonically increasing writes per key, and create reads for n keys.
    :read-n How many keys to read from at once. Default 2."
  [{:keys [keys read-n]}]
  {:checker (checker)
   ;; FIXME
   :generator (gen/mix [w-inc r])})