(ns reagent.ratom
  (:refer-clojure :exclude [atom])
  (:require-macros [reagent.ratom :refer [with-let]])
  (:require [reagent.impl.util :as util]
            [reagent.debug :refer-macros [dbg log warn error dev?]]))

(declare ^:dynamic *ratom-context*)
(defonce ^boolean debug false)
(defonce ^boolean silent false)
(defonce generation 0)
(defonce -running (clojure.core/atom 0))

(defn ^boolean reactive? []
  (not (nil? *ratom-context*)))


;;; Utilities

(defn running []
  (+ @-running))

(defn capture-derefed [f obj]
  (set! (.-cljsCaptured obj) (.-watching obj))
  (set! (.-cljsCapPos obj) 0)
  (when (dev?)
    (set! (.-ratomGeneration obj)
          (set! generation (inc generation))))
  (binding [*ratom-context* obj]
    (f)))

(defn captured [obj]
  (let [c (.-cljsCaptured obj)]
    (when-not (nil? c)
      (let [p (.-cljsCapPos obj)]
        (when (or (== p -1)
                  (== p (alength c)))
          obj)))))

(defn- -captured [obj]
  (let [obj (captured obj)]
    (when-not (nil? obj)
      (let [c (.-cljsCaptured obj)]
        (set! (.-cljsCaptured obj) nil)
        c))))

(defn- add-item [a x]
  (when (== -1 (.indexOf a x))
    (.push a x)))

(defn- notify-deref-watcher! [derefable]
  (when-some [obj *ratom-context*]
    (let [c (.-cljsCaptured obj)]
      (if (nil? c)
        (do (set! (.-cljsCapPos obj) -1)
            (set! (.-cljsCaptured obj) (array derefable)))
        ;; Try to avoid allocating new array
        (let [p (.-cljsCapPos obj)]
          (if (== p -1)
            (add-item c derefable)
            (if (identical? derefable (aget c p))
              (set! (.-cljsCapPos obj) (inc p))
              (let [c1 (set! (.-cljsCaptured obj) (.slice c 0 p))]
                (add-item c1 derefable)
                (set! (.-cljsCapPos obj) -1)))))))))

(defn- ^number arr-len [x]
  (if (nil? x) 0 (alength x)))

(defn- ^boolean arr-eq [x y]
  (let [len (arr-len x)]
    (and (== len (arr-len y))
         (loop [i 0]
           (or (== i len)
               (if (identical? (aget x i) (aget y i))
                 (recur (inc i))
                 false))))))

(def reaction-counter 0)

(defn- reaction-key [r]
  (if-some [k (.-reaction-id r)]
    k
    (->> reaction-counter inc
         (set! reaction-counter)
         (set! (.-reaction-id r)))))

(defn- check-watches [old new]
  (when debug
    (swap! -running + (- (count new) (count old))))
  new)

(defn- add-w [this key f]
  (let [w (.-watches this)]
    (set! (.-watches this) (check-watches w (assoc w key f)))))

(defn- remove-w [this key]
  (let [w (.-watches this)
        r -running]
    (set! (.-watches this) (check-watches w (dissoc w key)))))

(defn- notify-w [this old new]
  (reduce-kv (fn [_ k f]
               (f k this old new)
               nil)
             nil (.-watches this))
  nil)

(defn- pr-atom [a writer opts s]
  (-write writer (str "#<" s " "))
  (pr-writer (binding [*ratom-context* nil] (-deref a)) writer opts)
  (-write writer ">"))


;;; Queueing

(defonce rea-queue nil)
(def empty-context #js{})

(defn- rea-enqueue [r]
  (when (nil? rea-queue)
    (set! rea-queue (array))
    ;; Get around ugly circular dependency. TODO: Fix.
    (js/reagent.impl.batching.schedule))
  (.push rea-queue r))

(defn- run-queue [q]
  (dotimes [i (alength q)]
      (let [r (aget q i)]
        (._try-run r))))

(defn flush! []
  (when-some [q rea-queue]
    (set! rea-queue nil)
    (binding [*ratom-context* empty-context]
      (run-queue q))
    (assert (nil? (-captured empty-context)))))


;;; Atom

(defprotocol IReactiveAtom)

(deftype RAtom [^:mutable state meta validator ^:mutable watches]
  IAtom
  IReactiveAtom

  IEquiv
  (-equiv [o other] (identical? o other))

  IDeref
  (-deref [this]
    (notify-deref-watcher! this)
    state)

  IReset
  (-reset! [a new-value]
    (when-not (nil? validator)
      (assert (validator new-value) "Validator rejected reference state"))
    (let [old-value state]
      (set! state new-value)
      (when-not (nil? watches)
        (notify-w a old-value new-value))
      new-value))

  ISwap
  (-swap! [a f]          (-reset! a (f state)))
  (-swap! [a f x]        (-reset! a (f state x)))
  (-swap! [a f x y]      (-reset! a (f state x y)))
  (-swap! [a f x y more] (-reset! a (apply f state x y more)))

  IMeta
  (-meta [_] meta)

  IPrintWithWriter
  (-pr-writer [a w opts] (pr-atom a w opts "Atom:"))

  IWatchable
  (-notify-watches [this old new] (notify-w this old new))
  (-add-watch [this key f]        (add-w this key f))
  (-remove-watch [this key]       (remove-w this key))

  IHash
  (-hash [this] (goog/getUid this)))

(defn atom
  "Like clojure.core/atom, except that it keeps track of derefs."
  ([x] (RAtom. x nil nil nil))
  ([x & {:keys [meta validator]}] (RAtom. x meta validator nil)))



;;; track

(declare make-reaction)

(defonce cached-reactions (transient {}))

(defn- get-sub-map [k]
  (let [m (get cached-reactions k)]
    (if-not (nil? m)
      m
      (let [m (transient {})]
        (->> (assoc! cached-reactions k m)
             (set! cached-reactions))
        m))))

(defn- cached-reaction [f k1 k2 obj destroy]
  (let [m (get-sub-map k1)
        r (get m k2)]
    (if-not (nil? r)
      (-deref r)
      (if (nil? *ratom-context*)
        (f)
        (let [r (make-reaction
                 f :on-dispose (fn [x]
                                 (when debug (swap! -running dec))
                                 (let [c cached-reactions
                                       m (-> (get c k1)
                                             (dissoc! k2))
                                       c (if (zero? (count m))
                                           (dissoc! c k1)
                                           (assoc! c k1 m))]
                                   (set! cached-reactions c))
                                 (when-not (nil? obj)
                                   (set! (.-reaction obj) nil))
                                 (when-not (nil? destroy)
                                   (destroy x))
                                 nil))
              v (-deref r)]
          (->> (assoc! m k2 r)
               (assoc! cached-reactions k1)
               (set! cached-reactions))
          (when debug (swap! -running inc))
          (when-not (nil? obj)
            (set! (.-reaction obj) r))
          v)))))

(deftype Track [f args ^:mutable reaction]
  IReactiveAtom

  IDeref
  (-deref [this]
    (if-some [r reaction]
      (-deref r)
      (cached-reaction #(apply f args) (goog/getUid f) args this nil)))

  IEquiv
  (-equiv [_ other]
    (and (instance? Track other)
         (= key (.-key other))))

  IHash
  (-hash [_] (hash key))

  IPrintWithWriter
  (-pr-writer [a w opts] (pr-atom a w opts "Track:")))

(defn make-track [f args]
  (Track. f args nil))

(defn make-track! [f args]
  (let [t (make-track f args)
        r (make-reaction #(-deref t)
                         :auto-run true)]
    @r
    r))

(defn track [f & args]
  {:pre [(ifn? f)]}
  (make-track f args))

(defn track! [f & args]
  {:pre [(ifn? f)]}
  (make-track! f args))

;;; cursor

(deftype RCursor [ratom path ^:mutable reaction
                  ^:mutable state ^:mutable watches]
  IAtom
  IReactiveAtom

  IEquiv
  (-equiv [_ other]
    (and (instance? RCursor other)
         (= path (.-path other))
         (= ratom (.-ratom other))))

  Object
  (_peek [this]
    (binding [*ratom-context* nil]
      (-deref this)))

  (_set-state [this oldstate newstate]
    (when-not (identical? oldstate newstate)
      (set! state newstate)
      (when-not (nil? watches)
        (notify-w this oldstate newstate))))

  IDeref
  (-deref [this]
    (let [oldstate state
          newstate (if-some [r reaction]
                     (-deref r)
                     (let [f (if (satisfies? IDeref ratom)
                               #(get-in @ratom path)
                               #(ratom path))]
                       (cached-reaction f ratom path this nil)))]
      (._set-state this oldstate newstate)
      newstate))

  IReset
  (-reset! [this new-value]
    (let [oldstate state]
      (._set-state this oldstate new-value)
      (if (satisfies? IDeref ratom)
        (if (= path [])
          (reset! ratom new-value)
          (swap! ratom assoc-in path new-value))
        (ratom path new-value))
      new-value))

  ISwap
  (-swap! [a f]          (-reset! a (f (._peek a))))
  (-swap! [a f x]        (-reset! a (f (._peek a) x)))
  (-swap! [a f x y]      (-reset! a (f (._peek a) x y)))
  (-swap! [a f x y more] (-reset! a (apply f (._peek a) x y more)))

  IPrintWithWriter
  (-pr-writer [a w opts] (pr-atom a w opts (str "Cursor: " path)))

  IWatchable
  (-notify-watches [this old new] (notify-w this old new))
  (-add-watch [this key f]        (add-w this key f))
  (-remove-watch [this key]       (remove-w this key))

  IHash
  (-hash [_] (hash [ratom path])))

(defn cursor
  [src path]
  (assert (or (satisfies? IReactiveAtom src)
              (and (ifn? src)
                   (not (vector? src))))
          (str "src must be a reactive atom or a function, not "
               (pr-str src)))
  (RCursor. src path nil nil nil))



;;; with-let support

(defn with-let-destroy [v]
  (when-some [f (.-destroy v)]
    (f)))

(defn with-let-values [key]
  (if-some [c *ratom-context*]
    (cached-reaction array key (reaction-key c)
                     nil with-let-destroy)
    (array)))


;;;; reaction

(defprotocol IDisposable
  (dispose! [this]))

(defprotocol IRunnable
  (run [this]))

(defn- handle-reaction-change [this sender old new]
  (._handle-change this sender old new))


(deftype Reaction [f ^:mutable state ^:mutable ^boolean dirty?
                   ^:mutable watching ^:mutable watches
                   ^:mutable auto-run on-set on-dispose ^boolean nocache?]
  IAtom
  IReactiveAtom

  IWatchable
  (-notify-watches [this old new] (notify-w this old new))
  (-add-watch [this key f]        (add-w this key f))
  (-remove-watch [this key]
    (remove-w this key)
    (when (and (empty? watches)
               (nil? auto-run))
      (dispose! this)))

  IReset
  (-reset! [a newval]
    (assert (ifn? on-set) "Reaction is read only.")
    (let [oldval state]
      (set! state newval)
      (on-set oldval newval)
      (notify-w a oldval newval)
      newval))

  ISwap
  (-swap! [a f]          (-reset! a (f (._peek-at a))))
  (-swap! [a f x]        (-reset! a (f (._peek-at a) x)))
  (-swap! [a f x y]      (-reset! a (f (._peek-at a) x y)))
  (-swap! [a f x y more] (-reset! a (apply f (._peek-at a) x y more)))

  Object
  (_peek-at [this]
    (binding [*ratom-context* nil]
      (-deref this)))

  (_handle-change [this sender oldval newval]
    (when-not (identical? oldval newval)
      (if-not (nil? *ratom-context*)
        (if-not (nil? auto-run)
          (auto-run this)
          (when-not dirty?
            (set! dirty? true)
            (._run this)))
        (do
          (set! dirty? true)
          (rea-enqueue this))))
    nil)

  (_update-watching [this derefed]
    (let [wg watching]
      (set! watching derefed)
      (doseq [w derefed]
        (when (or (nil? wg)
                  (== -1 (.indexOf wg w)))
          (-add-watch w this handle-reaction-change)))
      (doseq [w wg]
        (when (or (nil? derefed)
                  (== -1 (.indexOf derefed w)))
          (-remove-watch w this))))
    nil)

  (_try-run [this other]
    (if-not (nil? auto-run)
      (auto-run this)
      (when (and dirty? (not (nil? watching)))
        (try
          (._run this)
          (catch :default e
            ;; Just log error: it will most likely pop up again at deref time.
            (when-not silent (error "Error in reaction:" e))
            (set! state nil)
            (notify-w this e nil)))))
    nil)

  (_run [this]
    (let [oldstate state
          res (capture-derefed f this)
          derefed (-captured this)]
      (set! dirty? false)
      (when-not (arr-eq derefed watching)
        (._update-watching this derefed))
      (when-not nocache?
        (set! state res)
        ;; Use = to determine equality from reactions, since
        ;; they are likely to produce new data structures.
        (when-not (or (nil? watches)
                      (= oldstate res))
          (notify-w this oldstate res)))
      res))

  IRunnable
  (run [this]
    (flush!)
    (._run this))

  IDeref
  (-deref [this]
    (when (nil? *ratom-context*)
      (flush!))
    (if-not (and (nil? auto-run) (nil? *ratom-context*))
      (do
        (notify-deref-watcher! this)
        (when dirty?
          (._run this)))
      (do
        (when dirty?
          (let [oldstate state]
            (set! state (f))
            (when-not (or (nil? watches)
                          (= oldstate state))
              (notify-w this oldstate state))))))
    state)

  IDisposable
  (dispose! [this]
    (let [s state
          wg watching]
      (set! watching nil)
      (set! state nil)
      (set! auto-run nil)
      (set! dirty? true)
      (doseq [w wg]
        (remove-watch w this))
      (when-not (nil? on-dispose)
        (on-dispose s)))
    nil)

  IEquiv
  (-equiv [o other] (identical? o other))

  IPrintWithWriter
  (-pr-writer [a w opts] (pr-atom a w opts (str "Reaction " (hash a) ":")))

  IHash
  (-hash [this] (reaction-key this)))


(defn make-reaction [f & {:keys [auto-run on-set on-dispose derefed no-cache
                                 capture]}]
  (let [runner (case auto-run
                 true run
                 auto-run)
        derefs (if-some [c capture]
                 (-captured c)
                 derefed)
        dirty (if (nil? derefs) true false)
        nocache (if (nil? no-cache) false no-cache)
        reaction (Reaction. f nil dirty nil nil
                            runner on-set on-dispose nocache)]
    (when-some [rid (some-> capture .-reaction-id)]
      (set! (.-reaction-id reaction) rid))
    (when-not (nil? derefed)
      (warn "using derefed is deprecated"))
    (when-not (nil? derefs)
      (when (dev?)
        (set! (.-ratomGeneration reaction)
              (.-ratomGeneration derefs)))
      (._update-watching reaction derefs))
    reaction))


;;; wrap

(deftype Wrapper [^:mutable state callback ^:mutable ^boolean changed
                  ^:mutable watches]

  IAtom

  IDeref
  (-deref [this]
    (when (dev?)
      (when (and changed (some? *ratom-context*))
        (warn "derefing stale wrap: "
              (pr-str this))))
    state)

  IReset
  (-reset! [this newval]
    (let [oldval state]
      (set! changed true)
      (set! state newval)
      (when-not (nil? watches)
        (notify-w this oldval newval))
      (callback newval)
      newval))

  ISwap
  (-swap! [a f]          (-reset! a (f state)))
  (-swap! [a f x]        (-reset! a (f state x)))
  (-swap! [a f x y]      (-reset! a (f state x y)))
  (-swap! [a f x y more] (-reset! a (apply f state x y more)))

  IEquiv
  (-equiv [_ other]
          (and (instance? Wrapper other)
               ;; If either of the wrappers have changed, equality
               ;; cannot be relied on.
               (not changed)
               (not (.-changed other))
               (= state (.-state other))
               (= callback (.-callback other))))

  IWatchable
  (-notify-watches [this old new] (notify-w this old new))
  (-add-watch [this key f]        (add-w this key f))
  (-remove-watch [this key]       (remove-w this key))

  IPrintWithWriter
  (-pr-writer [a w opts] (pr-atom a w opts "Wrap:")))

(defn make-wrapper [value callback-fn args]
  (Wrapper. value
            (util/partial-ifn. callback-fn args nil)
            false nil))
