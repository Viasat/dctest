(ns dctest.outcome)

(defn short-outcome [{:keys [outcome]}]
  (get {:pass "✓" :fail "F" :skip "S"} outcome "?"))

(defn failure? [{:keys [outcome]}]
  (= :fail outcome))

(defn pending? [{:keys [outcome]}]
  (nil? outcome))

(defn fail! [m & [error]]
  (merge m
         {:outcome :fail}
         (when error {:error error})))

(defn pass! [m] (assoc m :outcome :pass))
(defn skip! [m] (assoc m :outcome :skip))

;; This macros is duplicated in outcome.cljs and outcome.clj to support both
;; ShadowCLJS and nbb. Keep in sync.
(defmacro pending->
  "When the suite/test/step is still pending, thread it into
  the first form via ->. If that result is still pending, recurse
  to the second form, etc."
  [suite-test-or-step & forms]
  (let [sts (gensym)
        err (gensym)
        forms (map (fn [f] `(try
                              (if (dctest.outcome/pending? ~sts)
                                (-> ~sts ~f)
                                ~sts)
                              (catch js/Error ~err
                                (dctest.outcome/fail! ~sts
                                                      {:message (str "Exception thrown: " (.-message ~err))}))))
                   forms)]
    `(promesa.core/let [~sts ~suite-test-or-step
                        ~@(interleave (repeat sts) (butlast forms))]
       ~(if (empty? forms)
          sts
          (last forms)))))
