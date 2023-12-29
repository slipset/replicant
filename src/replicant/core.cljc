(ns replicant.core
  (:require [replicant.hiccup :as hiccup]
            [replicant.protocols :as r]))

(def ^:dynamic *dispatch* nil)

(defn get-event-handler
  "Returns the function to use for handling DOM events. Uses `handler` directly
  when it's a function or a string (assumed to be inline JavaScript, not really
  recommended), or a wrapper that dispatches through
  `replicant.core/*dispatch*`, if it is bound to a function. "
  [handler event]
  (or (when (fn? handler)
        handler)
      (when (ifn? *dispatch*)
        (fn [e]
          (*dispatch* {:replicant/event :replicant.event/dom-event} e handler)))
      (when (string? handler)
        ;; Strings could be inline JavaScript, so will be allowed when there is
        ;; no global event handler.
        handler)
      (throw (ex-info "Cannot use non-function event handler when replicant.core/*dispatch* is not bound to a function"
                      {:event event
                       :handler handler
                       :dispatch *dispatch*}))))

(defn get-life-cycle-hook
  "Returns the function to use to dispatch life-cycle hooks on an element. Uses
  `handler` directly when it's a function, or a wrapper that dispatches through
  `replicant.core/*dispatch*`, if it is bound to a function."
  [handler]
  (or (when (fn? handler)
        handler)
      (when (and handler (ifn? *dispatch*))
        (fn [e]
          (*dispatch* e handler)))
      (when handler
        (throw (ex-info "Cannot use non-function life-cycle hook when replicant.core/*dispatch* is not bound to a function"
                        {:handler handler
                         :dispatch *dispatch*})))))

(defn register-hook
  "Register the life-cycle hook from the corresponding virtual DOM node to call in
  `impl`, if any. The only time the hook in `old` is used is when `new` is
  `nil`, which means the node is unmounting. `details` is a vector of keywords
  that provide some detail about why the hook is invoked."
  [{:keys [hooks]} node new & [old details]]
  (when-let [hook (:replicant/on-update (if new (second new) (second old)))]
    (swap! hooks conj [hook node new old details])))

(defn call-hooks
  "Call the lifecycle hooks gathered during reconciliation."
  [[hook node new old details]]
  (let [f (get-life-cycle-hook hook)]
    (f (cond-> {:replicant/event :replicant.event/life-cycle
                :replicant/life-cycle
                (cond
                  (nil? old) :replicant/mount
                  (nil? new) :replicant/unmount
                  :else :replicant/update)
                :replicant/node node}
         details (assoc :replicant/details details)))))

(defn update-styles [renderer el new-styles old-styles]
  (run!
   #(let [new-style (% new-styles)]
      (cond
        (nil? new-style)
        (r/remove-style renderer el %)

        (not= new-style (% old-styles))
        (r/set-style renderer el % new-style)))
   (into (set (keys new-styles)) (keys old-styles))))

(defn update-classes [renderer el new-classes old-classes]
  (->> (remove (set new-classes) old-classes)
       (run! #(r/remove-class renderer el %)))
  (->> (remove (set old-classes) new-classes)
       (run! #(r/add-class renderer el %))))

(defn add-event-listeners [renderer el val]
  (run!
   (fn [[event handler]]
     (when-let [handler (get-event-handler handler event)]
       (r/set-event-handler renderer el event handler)))
   val))

(defn update-event-listeners [renderer el new-handlers old-handlers]
  (->> (remove (set (keys new-handlers)) (keys old-handlers))
       (run! #(r/remove-event-handler renderer el %)))
  (->> (remove #(= (val %) (get old-handlers (key %))) new-handlers)
       (add-event-listeners renderer el)))

(def xlinkns "http://www.w3.org/1999/xlink")
(def xmlns "http://www.w3.org/XML/1998/namespace")

(defn set-attr-val [renderer el attr v]
  (let [an (name attr)]
    (->> (cond-> {}
           (#{["x" "m" "l"] ;; ClojureScript
              [\x \m \l]} ;; Clojure
            (take 3 an))
           (assoc :ns xmlns)

           (#{["x" "l" "i" "n" "k" ":"]
              [\x \l \i \n \k \:]}
            (take 6 an))
           (assoc :ns xlinkns))
         (r/set-attribute renderer el an v))))

(defn update-attr [renderer el attr new old]
  (case attr
    :style (update-styles renderer el (:style new) (:style old))
    :classes (update-classes renderer el (:classes new) (:classes old))
    :on (update-event-listeners renderer el (:on new) (:on old))
    (if-let [v (attr new)]
      (when (not= v (attr old))
        (set-attr-val renderer el attr v))
      (r/remove-attribute renderer el (name attr)))))

(defn update-attributes [renderer el new-attrs old-attrs]
  (->> (into (set (keys new-attrs)) (keys old-attrs))
       (run! #(update-attr renderer el % new-attrs old-attrs)))
  (not= new-attrs old-attrs))

;; These setters are not strictly necessary - you could just call the update-*
;; functions with `nil` for `old`. The pure setters improve performance for
;; `create-node`

(defn set-styles [renderer el new-styles]
  (->> (keys new-styles)
       (run! #(r/set-style renderer el % (% new-styles)))))

(defn set-classes [renderer el new-classes]
  (->> new-classes
       (run! #(r/add-class renderer el %))))

(defn set-event-listeners [renderer el new-handlers]
  (add-event-listeners renderer el new-handlers))

(defn set-attr [renderer el attr new]
  (case attr
    :style (set-styles renderer el (:style new))
    :classes (set-classes renderer el (:classes new))
    :on (set-event-listeners renderer el (:on new))
    (set-attr-val renderer el attr (attr new))))

(defn set-attributes [renderer el new-attrs]
  (->> (keys new-attrs)
       (run! #(set-attr renderer el % new-attrs)))
  {:changed? true})

(defn- strip-nil-vals [m]
  (into {} (remove (comp nil? val) m)))

(defn- update-existing [m k & args]
  (if (contains? m k)
    (apply update m k args)
    m))

(defn prep-attributes [attrs]
  (-> attrs
      (dissoc :key :replicant/on-update ::ns)
      strip-nil-vals
      (update-existing :style strip-nil-vals)
      (update-existing :on strip-nil-vals)))

(defn namespace-hiccup [hiccup el-ns]
  (cond
    (string? hiccup) hiccup

    (map? (second hiccup))
    (assoc-in hiccup [1 ::ns] el-ns)

    :else
    (into [(first hiccup) {::ns el-ns}] (rest hiccup))))

(defn inflate-hiccup
  "Normalize hiccup form. Parses out class names and ids from the tag and returns
  a map of:

  - `:tag-name` - A string
  - `:attrs` - Parsed attributes
  - `:children` - A flattened list of children
  - `:ns` - Namespace for element (SVG)

  Some attributes receive special care:

  - `:classes` is a list of classes, extracted by parsing out dotted classes
    from the hiccup tag (e.g. \"heading\" in `:h1.heading`), as well as strings,
    keywords, or a collection of either from both `:class` and `:className`.
  - `:style` is a map of styles, even when the input hiccup provided a string
  - `:innerHTML` when this attribute is present, `:children` will be ignored

  ```clj
  (inflate-hiccup [:h1.heading \"Hello\"])
  ;;=>
  ;; {:tag-name \"h1\",
  ;;  :attrs {:classes (\"heading\")},
  ;;  :children [\"Heading\"]}
  ```"
  [hiccup]
  (let [inflated (hiccup/inflate hiccup)
        el-ns (or (::ns (:attrs inflated))
                  (when (= "svg" (:tag-name inflated))
                    "http://www.w3.org/2000/svg"))]
    (cond-> (update inflated :attrs prep-attributes)
      (:innerHTML (:attrs inflated)) (dissoc :children)
      el-ns (assoc :ns el-ns)
      
      (and el-ns (:children inflated))
      (update :children (fn [xs] (map #(namespace-hiccup % el-ns) xs))))))

(defn create-node
  "Create DOM node according to virtual DOM in `hiccup`. Register relevant
  life-cycle hooks from the new node or its descendants in `impl`. Returns
  the newly created node."
  [{:keys [renderer] :as impl} hiccup]
  (if (hiccup/hiccup? hiccup)
    (let [{:keys [tag-name attrs children ns]} (inflate-hiccup hiccup)
          node (r/create-element renderer tag-name (when ns {:ns ns}))]
      (set-attributes renderer node attrs)
      (run! #(r/append-child renderer node (create-node impl %)) children)
      (register-hook impl node hiccup)
      node)
    (r/create-text-node renderer (str hiccup))))

(defn same?
  "Two elements are considered the \"same\" if they are both hiccup elements with
  the same tag name and the same key (or both have no key) - or they are both
  strings.

  Sameness in this case indicates that the node can be used for reconciliation
  instead of creating a new node from scratch."
  [a b]
  (or (and (string? a) (string? b))
      (and (= (get-in a [1 :key])
              (get-in b [1 :key]))
           (= (hiccup/get-tag-name a) (hiccup/get-tag-name b)))))

(defn changed?
  "Returns `true` when nodes have changed in such a way that a new node should be
  created. `changed?` is not the strict complement of `same?`, because it does
  not consider any two strings the same - only the exact same string."
  [new old]
  (or (not= (type old) (type new))
      (and (not (hiccup/hiccup? old)) (not= new old))
      (not= (hiccup/get-tag-name old) (hiccup/get-tag-name new))))

;; reconcile* and update-children are mutually recursive
(declare reconcile*)

(defn index-of [f xs]
  (loop [n 0
         xs (seq xs)]
    (cond
      (nil? xs) -1
      (f (first xs)) n
      :else (recur (inc n) (next xs)))))

(defn update-children [impl el new old]
  (let [r (:renderer impl)
        get-child #(r/get-child r el %)]
    (loop [new-c (:children new)
           old-c (:children old)
           n 0
           move-n 0
           n-children (count (:children old))
           changed? false]
      (let [new-hiccup (first new-c)
            old-hiccup (first old-c)]
        (cond
          ;; Both empty, we're done
          (and (nil? new-c) (nil? old-c))
          changed?

          ;; There are old nodes where there are no new nodes: delete
          (nil? new-c)
          (let [child (r/get-child r el n)]
            (r/remove-child r el child)
            (register-hook impl child nil old-hiccup)
            (recur nil (next old-c) n move-n (dec n-children) true))

          ;; There are new nodes where there were no old ones: create
          (nil? old-c)
          (do
            (run! #(r/append-child r el (create-node impl %)) new-c)
            true)

          ;; It's "the same node" (e.g. reusable), reconcile
          (same? new-hiccup old-hiccup)
          (let [node-changed? (not= new-hiccup old-hiccup)]
            (reconcile* impl el new-hiccup old-hiccup {:index n})
            (when (and (not node-changed?) (< n move-n))
              (register-hook impl (get-child n) new-hiccup old-hiccup [:replicant/move-node]))
            (recur (next new-c) (next old-c) (inc n) move-n n-children (or changed? node-changed?)))

          ;; Nodes have moved. Find the original position of the two nodes
          ;; currently being considered, and move the one that is currently the
          ;; furthest away, create node or remove node accordingly.
          :else
          (let [o-idx (index-of #(same? new-hiccup %) old-c)
                n-idx (index-of #(same? old-hiccup %) new-c)]
            (cond
              ;; new-hiccup represents a node that did not previously exist,
              ;; create it
              (< o-idx 0)
              (let [child (create-node impl new-hiccup)]
                (if (<= n-children n)
                  (r/append-child r el child)
                  (r/insert-before r el child (get-child n)))
                (recur (next new-c) old-c (inc n) move-n (inc n-children) true))

              ;; the old node no longer exists, remove it
              (< n-idx 0)
              (let [child (get-child n)]
                (r/remove-child r el child)
                (register-hook impl child nil old-hiccup)
                (recur new-c (next old-c) n move-n (dec n-children) true))

              (< o-idx n-idx)
              ;; The new node needs to be moved back
              ;;
              ;; Old: 1 2 3
              ;; New: 2 3 1
              ;;
              ;; old-hiccup: 1, n-idx: 2
              ;; new-hiccup: 2, o-idx: 1
              ;;
              ;; The old node is now at the end, move it there and continue. It
              ;; will be reconciled when the loop reaches it.
              ;;
              ;; append-child 0
              ;; Old: 2 3 1
              ;; New: 2 3 1
              (let [idx (+ n n-idx 1)
                    child (get-child n)]
                (if (< idx n-children)
                  (r/insert-before r el child (get-child idx))
                  (r/append-child r el child))
                (register-hook impl child (nth new-c n-idx) old-hiccup [:replicant/move-node])
                (recur
                 new-c
                 (concat (take n-idx (next old-c)) [(first old-c)] (drop (inc n-idx) old-c))
                 n
                 (dec idx)
                 n-children
                 true))

              :else
              ;; The new node needs to be brought to the front
              ;;
              ;; Old: 1 2 3
              ;; New: 3 1 2
              ;;
              ;; old-hiccup: 1, n-idx: 1
              ;; new-hiccup: 3, o-idx: 2
              ;;
              ;; The new node used to be at the end, move it to the front and
              ;; reconcile it, then continue with the rest of the nodes.
              ;;
              ;; insert-before 3 1
              ;; Old: 1 2
              ;; New: 1 2
              (let [idx (+ n o-idx)
                    child (get-child idx)
                    corresponding-old-hiccup (nth old-c o-idx)]
                (r/insert-before r el child (get-child n))
                (reconcile* impl el new-hiccup corresponding-old-hiccup {:index n})
                (when (= new-hiccup corresponding-old-hiccup)
                  (register-hook impl child new-hiccup corresponding-old-hiccup [:replicant/move-node]))
                (recur (next new-c) (concat (take o-idx old-c) (drop (inc o-idx) old-c)) (inc n) (inc (+ n o-idx)) n-children true)))))))))

(defn reconcile* [{:keys [renderer] :as impl} el new old {:keys [index]}]
  (cond
    (= new old)
    nil

    (nil? new)
    (let [child (r/get-child renderer el index)]
      (r/remove-child renderer el child)
      (register-hook impl child new old))

    ;; The node at this index is of a different type than before, replace it
    ;; with a fresh one. Use keys to avoid ending up here.
    (changed? new old)
    (let [node (create-node impl new)]
      (r/replace-child renderer el node (r/get-child renderer el index)))

    ;; Update the node's attributes and reconcile its children
    (not (string? new))
    (let [old* (inflate-hiccup old)
          new* (inflate-hiccup new)
          child (r/get-child renderer el index)
          attrs-changed? (update-attributes renderer child (:attrs new*) (:attrs old*))
          children-changed? (update-children impl child new* old*)
          attrs-changed? (or attrs-changed?
                             (not= (:replicant/on-update (second new))
                                   (:replicant/on-update (second old))))]
      (->> [(when attrs-changed? :replicant/updated-attrs)
            (when children-changed? :replicant/updated-children)]
           (remove nil?)
           (register-hook impl child new old)))))

(defn reconcile
  "Reconcile the DOM in `el` by diffing the `new` hiccup with the `old` hiccup. If
  there is no `old` hiccup, `reconcile` will create the DOM as per `new`.
  Assumes that the DOM in `el` is in sync with `old` - if not, this will
  certainly not produce the desired result."
  [renderer el new & [old]]
  (let [impl {:renderer renderer
              :hooks (atom [])}]
    (if (nil? old)
      (r/append-child renderer el (create-node impl new))
      (reconcile* impl el new old {:index 0}))
    (let [hooks @(:hooks impl)]
      (run! call-hooks hooks)
      {:hooks hooks})))
