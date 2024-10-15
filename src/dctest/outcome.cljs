(ns dctest.outcome)

(defn short-outcome [{:keys [outcome]}]
  (get {:passed "✓" :failed "F" :skipped "S"} outcome "?"))

(defn failure? [{:keys [outcome]}]
  (= :failed outcome))

(defn passed? [{:keys [outcome]}]
  (= :passed outcome))

(defn pending? [{:keys [outcome]}]
  (= :pending outcome))

(defn fail! [m & [error]]
  (merge m
         {:outcome :failed}
         (when error {:error error})))

(defn pass! [m] (assoc m :outcome :passed))
(defn skip! [m] (assoc m :outcome :skipped))

;; This macro is duplicated in outcome.cljs and outcome.clj to support both
;; ShadowCLJS and nbb. Keep in sync.
(defmacro pending->
  "When the suite/test/step is still pending, thread it into
  the first form via ->. If that result is still pending, recurse
  to the second form, etc."
  [suite-test-or-step & forms]
  (let [sts (gensym)
        err (gensym)
        ;; Transform forms to check if pending? before delegating to
        ;; normal -> behavior; otherwise, simply return suite/test/step
        ;; unchanged. Wrap in try/catch and fail the suite/test/step if
        ;; errors are thrown (short-circuiting all following forms).
        forms (map (fn [f] `(try
                              (if (dctest.outcome/pending? ~sts)
                                (-> ~sts ~f)
                                ~sts)
                              (catch js/Error ~err
                                (dctest.outcome/fail! ~sts
                                                      {:message (str "Exception thrown: " (.-message ~err))}))))
                   forms)]
    ;; Repeatedly (re)define `sts` gensym with each transformed form above.
    ;; Conceptually expanding:
    ;;
    ;; (pending-> x f g h)
    ;;
    ;; to:
    ;;
    ;; (P/let [sts x
    ;;         sts (f' sts)
    ;;         sts (g' sts)]
    ;;   (h' sts))
    ;;
    ;; See also: clojure.core/some-> implementation
    `(promesa.core/let [~sts ~suite-test-or-step
                        ~@(interleave (repeat sts) (butlast forms))]
       ~(if (empty? forms)
          sts
          (last forms)))))
