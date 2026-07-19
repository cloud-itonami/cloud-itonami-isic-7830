(ns payroll.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root cloud-itonami
  flagship rollout): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`payroll.operation` -> `payroll.policy` -> `payroll.store`) through a
  scenario adapted from this repo's own `payroll.sim` demo driver
  (`clojure -M:dev:run`, confirmed to run correctly against the real
  seeded roster before this file was written -- this repo's own sim
  driver uses ids that DO match `payroll.store/demo-data`, so its
  scenario shape and numbers were safe to reuse rather than author from
  scratch), trimmed to a representative subset (two auto-commits, two
  escalate->approve->commit lifecycles, and three distinct HARD-hold
  reasons -- source-provenance-gate, tax-withholding-calculation-gate,
  filing-deadline-gate) and rendered deterministically -- no invented
  numbers, no timestamps in the page content, byte-identical across
  reruns against the same seed (verified by diffing two consecutive
  runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [payroll.store :as store]
            [payroll.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "po-1" :actor-role :payroll-operator :phase 3})

(def ^:private officer
  {:actor-id "co-1" :actor-role :compliance-officer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "compliance-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, at phase 3 (supervised-auto): emp-1 clears a
  clean payroll run (source cited, withholding within the 20% tolerance
  -> auto-commit, no human touch) and the Q1 941 filing clears before its
  statutory due date (-> auto-commit); emp-2 (seeded `:status :disputed`)
  runs a governor-clean payroll computation that nonetheless ALWAYS
  escalates to a human because the employee's status is disputed
  (approved); emp-1 files a payroll dispute, which ALWAYS escalates to a
  human at any phase, any confidence (approved). Then three independent
  HARD holds, each isolated to exactly one PayrollGovernor rule so none
  of the others fire alongside it: emp-3's payroll run is proposed with
  no withholding-table citation at all (source-provenance-gate); emp-1's
  payroll run is proposed with a withholding figure grossly outside the
  20% tolerance of the R0 flat-rate estimate despite a valid citation
  (tax-withholding-calculation-gate); the Q1 941 filing is resubmitted
  as-of a date after its statutory due date (filing-deadline-gate).
  Every HARD hold never reaches a human. Returns the resulting store --
  every field read by `render` below is real governor/store output, not
  a hand-typed copy."
  []
  (let [db    (store/seed-db)
        actor (op/build db)]

    (exec! actor "e1-payroll-clean"
           {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
            :gross 3692.31M :withholding 812.31M
            :source {:class :irs-federal-withholding-table :ref "irs-pub-15-t:2026"}}
           operator)

    (exec! actor "fil-clean-submit"
           {:op :filing/submit :subject "fil-2026-q1-941" :filing-id "fil-2026-q1-941"
            :form "941" :period "2026-Q1" :employer-id "er-100"
            :as-of "2026-04-15" :due-date "2026-04-30"}
           operator)

    (exec! actor "e2-payroll-disputed"
           {:op :payroll/process :subject "emp-2" :employee-id "emp-2" :period "2026-P01"
            :gross 3230.77M :withholding 193.85M
            :source {:class :state-withholding-table :ref "ca-de-44:2026"}}
           operator)
    (approve! actor "e2-payroll-disputed")

    (exec! actor "e1-dispute-request"
           {:op :dispute/request :subject "emp-1" :disputed-field :withholding :claim 800.00M}
           officer)
    (approve! actor "e1-dispute-request")

    (exec! actor "e3-unsourced"
           {:op :payroll/process :subject "emp-3" :employee-id "emp-3" :period "2026-P01"
            :gross 4230.77M :withholding 253.85M :unsourced? true}
           operator)

    (exec! actor "e1-bad-withholding"
           {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P03"
            :gross 3692.31M :withholding 2500.00M
            :source {:class :irs-federal-withholding-table :ref "irs-pub-15-t:2026"}}
           operator)

    (exec! actor "fil-late-submit"
           {:op :filing/submit :subject "fil-2026-q1-941" :filing-id "fil-2026-q1-941"
            :form "941" :period "2026-Q1" :employer-id "er-100"
            :as-of "2026-05-15" :due-date "2026-04-30"}
           operator)
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :policy-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-rejected (:t f)) "<span class=\"critical\">approval rejected</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- employee-row [ledger {:keys [id name jurisdiction status annual-salary]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc name) (esc (clojure.core/name (or jurisdiction :n-a)))
          (esc (clojure.core/name (or status :n-a))) (esc annual-salary) (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `payroll.policy`/`payroll.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:payroll/process</code></td><td><span class=\"ok\">auto-commit when clean, non-disputed employee</span></td></tr>"
   "        <tr><td><code>:filing/submit</code></td><td><span class=\"ok\">auto-commit when clean, form covered &amp; before due date</span></td></tr>"
   "        <tr><td><code>:disclosure/query</code></td><td><span class=\"warn\">governed read &middot; column set capped by contract tier</span></td></tr>"
   "        <tr><td><code>:dispute/request</code></td><td><span class=\"warn\">ALWAYS human approval, any phase, any confidence</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        employees (store/employees-of db "er-100")
        employee-rows (str/join "\n" (map (partial employee-row ledger) employees))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-7830 &middot; payroll-processing</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Payroll processing &amp; administration (ISIC 7830) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · payroll commit &amp; filing submission gated by 8-check PayrollGovernor</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Payroll roster (employer er-100)</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>payroll.store</code> via <code>payroll.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Employee</th><th>Name</th><th>Jurisdiction</th><th>Status</th><th>Annual salary</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     employee-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (PayrollGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Withholding amounts are checked against the R0 flat-rate estimate tolerance and must cite an allowed withholding-table class; filings must cite a covered form and be submitted on or before the statutory due date; a disputed employee's payroll always escalates to a human.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/employees-of db "er-100")) "employees )")))
