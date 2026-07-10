(ns payroll.store
  "SSoT for the payroll-processing actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. Deterministic default.
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible
                       EAV store. Pure `.cljc`.

  Entity shapes (ADR-0001): an employer (client, remains the legal
  employer), an employee (payroll roster entry, `:status :active|:disputed`),
  a payroll-run (one computed pay period: gross/withholding/net, source-
  cited), a filing (a tax-form submission with a statutory due date), and a
  subscriber contract (employer tenant × tier, licensed disclosure). There
  is NO field anywhere in this schema for employment decisions (hire/fire/
  discipline) or benefits eligibility determination — this actor only
  computes and submits payroll, it never makes an employment-status
  decision (that stays with the client employer, the same structural
  exclusion class as `cloud-itonami-isic-6311`'s no-trade-execution
  boundary).

  The ledger stays append-only on every backend."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (employer [s id])
  (employee [s id])
  (employees-of [s employer-id])
  (payroll-run [s id])
  (filing [s id])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-employers [s employers])
  (with-employees [s employees])
  (with-payroll-runs [s runs])
  (with-filings [s filings])
  (with-contracts [s contracts]))

;; ───────────────────────── demo data (fictitious) ─────────────────────

(defn demo-data
  "Entirely fictitious employers/employees so the actor + tests run offline
  and no real payroll data is ever asserted by this repository. `emp-2`
  carries a demo `:status :disputed` flag purely to exercise the
  disputed-employee-status governor gate."
  []
  {:employers
   {"er-100" {:id "er-100" :name "出島貿易株式会社(デモ)" :jurisdiction :us-federal}}
   :employees
   {"emp-1" {:id "emp-1" :name "Jane Doe (demo)" :employer-id "er-100"
             :jurisdiction :us-federal :status :active :annual-salary 96000.00M}
    "emp-2" {:id "emp-2" :name "John Roe (demo)" :employer-id "er-100"
             :jurisdiction :us-ca :status :disputed :annual-salary 84000.00M}
    "emp-3" {:id "emp-3" :name "Alex Kim (demo)" :employer-id "er-100"
             :jurisdiction :us-ny :status :active :annual-salary 110000.00M}}
   :filings
   {"fil-2026-q1-941" {:id "fil-2026-q1-941" :form "941" :period "2026-Q1"
                        :employer-id "er-100" :status :pending
                        :due-date "2026-04-30"}}
   :contracts
   {"tenant-er-100" {:tenant "tenant-er-100" :tier :tier/employer :active? true}
    "tenant-basic"  {:tenant "tenant-basic" :tier :tier/basic :active? true}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (employer [_ id] (get-in @a [:employers id]))
  (employee [_ id] (get-in @a [:employees id]))
  (employees-of [_ employer-id]
    (->> (vals (:employees @a)) (filter #(= employer-id (:employer-id %))) (sort-by :id)))
  (payroll-run [_ id] (get-in @a [:payroll-runs id]))
  (filing [_ id] (get-in @a [:filings id]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :payroll-commit (swap! a assoc-in [:payroll-runs (:id value)] value)
      :filing-submit  (swap! a update-in [:filings (:id value)] merge value)
      :correction-apply (swap! a update-in [:payroll-runs (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-employers [s es]    (when (seq es) (swap! a assoc :employers es)) s)
  (with-employees [s es]    (when (seq es) (swap! a assoc :employees es)) s)
  (with-payroll-runs [s rs] (when (seq rs) (swap! a assoc :payroll-runs rs)) s)
  (with-filings [s fs]      (when (seq fs) (swap! a assoc :filings fs)) s)
  (with-contracts [s cts]   (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :payroll-runs {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  {:employer/id     {:db/unique :db.unique/identity}
   :employee/id     {:db/unique :db.unique/identity}
   :payroll-run/id  {:db/unique :db.unique/identity}
   :filing/id       {:db/unique :db.unique/identity}
   :contract/tenant {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- employer->tx [{:keys [id name jurisdiction]}]
  (cond-> {:employer/id id}
    name         (assoc :employer/name name)
    jurisdiction (assoc :employer/jurisdiction jurisdiction)))

(defn- pull->employer [m]
  (when (:employer/id m)
    {:id (:employer/id m) :name (:employer/name m) :jurisdiction (:employer/jurisdiction m)}))

(def ^:private employer-pull [:employer/id :employer/name :employer/jurisdiction])

(defn- employee->tx [{:keys [id name employer-id jurisdiction status annual-salary]}]
  (cond-> {:employee/id id}
    name           (assoc :employee/name name)
    employer-id    (assoc :employee/employer-id employer-id)
    jurisdiction   (assoc :employee/jurisdiction jurisdiction)
    status         (assoc :employee/status status)
    annual-salary  (assoc :employee/annual-salary (enc annual-salary))))

(defn- pull->employee [m]
  (when (:employee/id m)
    {:id (:employee/id m) :name (:employee/name m) :employer-id (:employee/employer-id m)
     :jurisdiction (:employee/jurisdiction m) :status (:employee/status m)
     :annual-salary (dec* (:employee/annual-salary m))}))

(def ^:private employee-pull
  [:employee/id :employee/name :employee/employer-id :employee/jurisdiction
   :employee/status :employee/annual-salary])

(defn- payroll-run->tx [{:keys [id employee-id period gross withholding net source]}]
  {:payroll-run/id id :payroll-run/employee-id employee-id :payroll-run/period period
   :payroll-run/gross (enc gross) :payroll-run/withholding (enc withholding)
   :payroll-run/net (enc net) :payroll-run/source (enc source)})

(defn- pull->payroll-run [m]
  (when (:payroll-run/id m)
    {:id (:payroll-run/id m) :employee-id (:payroll-run/employee-id m)
     :period (:payroll-run/period m) :gross (dec* (:payroll-run/gross m))
     :withholding (dec* (:payroll-run/withholding m)) :net (dec* (:payroll-run/net m))
     :source (dec* (:payroll-run/source m))}))

(def ^:private payroll-run-pull
  [:payroll-run/id :payroll-run/employee-id :payroll-run/period :payroll-run/gross
   :payroll-run/withholding :payroll-run/net :payroll-run/source])

(defn- filing->tx [{:keys [id form period employer-id status due-date]}]
  (cond-> {:filing/id id}
    form         (assoc :filing/form form)
    period       (assoc :filing/period period)
    employer-id  (assoc :filing/employer-id employer-id)
    status       (assoc :filing/status status)
    due-date     (assoc :filing/due-date due-date)))

(defn- pull->filing [m]
  (when (:filing/id m)
    {:id (:filing/id m) :form (:filing/form m) :period (:filing/period m)
     :employer-id (:filing/employer-id m) :status (:filing/status m)
     :due-date (:filing/due-date m)}))

(def ^:private filing-pull
  [:filing/id :filing/form :filing/period :filing/employer-id :filing/status :filing/due-date])

(defn- contract->tx [{:keys [tenant tier active?]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active?})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m) :active? (:contract/active m)}))

(def ^:private contract-pull [:contract/tenant :contract/tier :contract/active])

(defrecord DatomicStore [conn]
  Store
  (employer [_ id] (pull->employer (d/pull (d/db conn) employer-pull [:employer/id id])))
  (employee [_ id] (pull->employee (d/pull (d/db conn) employee-pull [:employee/id id])))
  (employees-of [_ employer-id]
    (->> (d/q '[:find [?id ...] :in $ ?eid
                :where [?e :employee/employer-id ?eid] [?e :employee/id ?id]]
              (d/db conn) employer-id)
         (map #(pull->employee (d/pull (d/db conn) employee-pull [:employee/id %])))
         (sort-by :id)))
  (payroll-run [_ id] (pull->payroll-run (d/pull (d/db conn) payroll-run-pull [:payroll-run/id id])))
  (filing [_ id] (pull->filing (d/pull (d/db conn) filing-pull [:filing/id id])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :payroll-commit (d/transact! conn [(payroll-run->tx value)])
      :filing-submit
      (d/transact! conn [(filing->tx (merge (filing s (:id value)) value))])
      :correction-apply
      (d/transact! conn [(payroll-run->tx (merge (payroll-run s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-employers [s es]
    (when (seq es) (d/transact! conn (mapv employer->tx (vals es)))) s)
  (with-employees [s es]
    (when (seq es) (d/transact! conn (mapv employee->tx (vals es)))) s)
  (with-payroll-runs [s rs]
    (when (seq rs) (d/transact! conn (mapv payroll-run->tx (vals rs)))) s)
  (with-filings [s fs]
    (when (seq fs) (d/transact! conn (mapv filing->tx (vals fs)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [employers employees payroll-runs filings contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-employers employers) (with-employees employees)
         (with-payroll-runs payroll-runs) (with-filings filings)
         (with-contracts contracts)))))

(defn datomic-seed-db []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
