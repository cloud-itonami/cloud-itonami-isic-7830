(ns payroll.policy
  "PayrollGovernor — the independent compliance layer that earns the
  PayrollProcessor-LLM the right to commit a payroll run, submit a filing,
  or resolve a dispute. The LLM has no notion of statutory withholding
  tolerance, filing deadlines, or a disputed employee's frozen status, so
  this MUST be a separate system able to *reject* a proposal and fall back
  to HOLD — this actor's analog of `cloud-itonami-isic-6311`'s
  MarketDataGovernor.

  Eight checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                          — does actor-role have permission?
    2. tax-withholding-calculation-gate — does a computed withholding fall
                                          within tolerance of the R0
                                          jurisdiction estimate?
    3. filing-deadline-gate           — is the filing's form covered by R0
                                          and is `:as-of` on/before its due
                                          date?
    4. source-provenance-gate         — does the payroll run cite an
                                          allowed withholding-table class
                                          matching the employee's
                                          jurisdiction?
    5. licensed-disclosure            — is there an active employer
                                          contract, and does the requested
                                          column set stay within its tier?
    6. confidence floor               — LLM confidence below threshold →
                                          escalate.
    7. disputed-employee-status gate  — the employee's status is
                                          `:disputed` → always escalate.
    8. dispute requests               — `:dispute/request` NEVER auto-
                                          resolves, at any confidence, any
                                          phase."
  (:require [clojure.set :as set]
            [payroll.facts :as facts]
            [payroll.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def tolerance-pct
  "Maximum fractional deviation a proposed withholding amount may have from
  the R0 flat-rate demo estimate before the tax-withholding-calculation-gate
  rejects it outright. HARD ceiling — no confidence score waives it."
  0.20)

(def confidence-floor 0.6)

(def permissions
  {:payroll-operator #{:payroll/process :filing/submit}
   :compliance-officer #{:payroll/process :filing/submit :dispute/request}
   :employer-subscriber #{:disclosure/query}})

(def tier-columns
  (let [base #{:employee-id :period :gross :withholding :net}
        employer-extra #{:source :filing-status}]
    {:tier/basic    base
     :tier/employer (into base employer-extra)}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- tax-withholding-violations
  "Only `:payroll/process` asserts a new withholding amount."
  [{:keys [op]} proposal st]
  (when (= op :payroll/process)
    (let [{:keys [employee-id withholding gross]} (:value proposal)
          emp    (store/employee st employee-id)
          rate   (get facts/demo-flat-rate-estimate (:jurisdiction emp))]
      (when (and rate gross withholding)
        (let [expected (* gross rate)
              dev      (/ (double (abs (- withholding expected))) (double expected))]
          (when (> dev tolerance-pct)
            [{:rule :tax-withholding-calculation-gate
              :detail (str "源泉徴収額が許容乖離を超過: expected≈" expected
                           " proposed=" withholding " dev=" dev " > " tolerance-pct)}]))))))

(defn- filing-deadline-violations
  [{:keys [op]} proposal]
  (when (= op :filing/submit)
    (let [{:keys [form as-of]} proposal
          fc (facts/filing-for form)]
      (cond
        (nil? fc)
        [{:rule :filing-deadline-gate :detail (str "R0 filing-catalog に無い form: " form)}]

        (and as-of (:due-date proposal)
             (pos? (compare as-of (:due-date proposal))))
        [{:rule :filing-deadline-gate
          :detail (str "提出時点(" as-of ")が申告期限(" (:due-date proposal) ")を超過")}]

        :else nil))))

(defn- source-provenance-violations
  "A payroll-run proposal must cite an allowed withholding-table class —
  a missing source, or a `:class` outside `payroll.facts/
  allowed-source-classes`, is a HARD rejection regardless of confidence."
  [{:keys [op]} proposal]
  (when (= op :payroll/process)
    (let [src (:source proposal)]
      (when (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-provenance-gate
          :detail (str "出典が無いか許可された出典クラスでない: " (pr-str src))}]))))

(defn- licensed-disclosure-violations
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :disclosure/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- disputed-employee?
  [st employee-id]
  (when employee-id
    (= :disputed (:status (store/employee st employee-id)))))

(defn check
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (tax-withholding-violations request proposal st)
                              (filing-deadline-violations request proposal)
                              (source-provenance-violations request proposal)
                              (licensed-disclosure-violations request context proposal st)))
        conf        (:confidence proposal 0.0)
        low?        (< conf confidence-floor)
        employee-id (or (get-in proposal [:value :employee-id]) (:subject request))
        disputed?   (disputed-employee? st employee-id)
        dispute?    (= :dispute/request (:op request))
        hard?       (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not disputed?) (not dispute?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? disputed? dispute?))
     :disputed?    disputed?
     :dispute?     dispute?}))

(defn hold-fact
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
