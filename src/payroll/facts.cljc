(ns payroll.facts
  "R0 source-basis catalog — the ONLY withholding-table classes and filing-
  deadline references the PayrollGovernor will accept as a citation for a
  computed payroll run or a filing submission (mirrors `cloud-itonami-
  isic-6311`'s `marketdata.facts` discipline: honesty over coverage). Every
  entry here is a real, citable, public authority publication. Adding
  coverage means adding a real, citable catalog entry — never fabricating
  one, and never inventing a withholding rate or a filing deadline.")

(def withholding-catalog
  "Each entry: {:id :name :jurisdiction :class :tax-year :url}. `:class` is
  the value that must appear in a payroll proposal's `:source :class` for
  the source-provenance-gate to accept it."
  [{:id :irs-pub-15-t
    :name "IRS Publication 15-T (Federal Income Tax Withholding Methods)"
    :jurisdiction :us-federal :class :irs-federal-withholding-table
    :tax-year 2026 :url "https://www.irs.gov/publications/p15t"}
   {:id :ca-de-44
    :name "California Employer's Guide (DE 44) withholding schedules"
    :jurisdiction :us-ca :class :state-withholding-table
    :tax-year 2026 :url "https://edd.ca.gov/en/payroll_taxes/rates_and_withholding"}
   {:id :ny-it-2104
    :name "New York State IT-2104 withholding tables"
    :jurisdiction :us-ny :class :state-withholding-table
    :tax-year 2026 :url "https://www.tax.ny.gov/pdf/publications/withholding/nys50t_nys.pdf"}])

(def filing-catalog
  "Each entry: {:id :form :jurisdiction :period :due-day-of-month
  :months-after-period-end}. Real, honest deadline references only — R0
  covers federal Form 941 (quarterly) and Form W-2 (annual) only."
  [{:id :form-941-q :form "941" :jurisdiction :us-federal :period :quarterly
    :months-after-period-end 1 :due-day-of-month 31}
   {:id :form-w2-annual :form "W-2" :jurisdiction :us-federal :period :annual
    :months-after-period-end 1 :due-day-of-month 31}])

(def demo-flat-rate-estimate
  "A simplified FLAT-RATE approximation of expected withholding per
  jurisdiction, used ONLY by the tax-withholding-calculation-gate's
  tolerance check in this demo/R0 build. Real production withholding is
  bracket-based (progressive), computed from the actual `withholding-
  catalog` publications — this map exists so the governor has a cheap,
  deterministic 'is this proposal in the right ballpark' check without
  reimplementing the IRS/state bracket tables in `.cljc`. An operator
  replacing this with a real bracket calculator only needs to swap this map
  (and the tolerance check that reads it) — the governor/ledger/phase
  layers are unaffected."
  {:us-federal 0.22 :us-ca 0.06 :us-ny 0.06})

(def allowed-source-classes
  "The set of `:source :class` values the source-provenance-gate will
  accept anywhere. A closed set — a class not in `withholding-catalog`
  (e.g. :inference, :estimated, :prior-year-carryover) must be rejected,
  not silently accepted because it looks like a keyword."
  (into #{} (map :class withholding-catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全米全州の源泉徴収' in prose, 1 federal + 2 state tables +
  2 federal filing forms in fact)."
  []
  {:withholding-jurisdictions (into (sorted-set) (map :jurisdiction withholding-catalog))
   :withholding-source-count (count withholding-catalog)
   :filing-forms (into (sorted-set) (map :form filing-catalog))
   :note (str "R0 scope: federal + CA + NY withholding tables (tax year 2026), "
              "federal Form 941/W-2 filing deadlines only. Extend only by "
              "appending a real, citable catalog entry — never fabricate a "
              "withholding rate or filing deadline.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn withholding-table-for [jurisdiction]
  (first (filter #(= jurisdiction (:jurisdiction %)) withholding-catalog)))

(defn filing-for [form]
  (first (filter #(= form (:form %)) filing-catalog)))
