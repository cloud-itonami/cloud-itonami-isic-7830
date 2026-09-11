(ns payroll.policy-contract-test
  "The governor contract as executable tests. The single invariant under
  test: PayrollProcessor-LLM never commits a payroll run/files a return/
  resolves a dispute the PayrollGovernor would reject, and every decision
  (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [payroll.store :as store]
            [payroll.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "po-1" :actor-role :payroll-operator :phase 3})
(def officer  {:actor-id "co-1" :actor-role :compliance-officer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-process-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
                   :gross 3692.31M :withholding 812.31M
                   :source {:class :irs-federal-withholding-table :ref "irs-pub-15-t:2026"}}
                  operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 812.31M (:withholding (store/payroll-run db "emp-1-2026-P01"))))
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "an :employer-subscriber role has no process permission -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
                     :gross 3692.31M :withholding 812.31M
                     :source {:class :irs-federal-withholding-table :ref "demo"}}
                    {:actor-id "sub-1" :actor-role :employer-subscriber :phase 3})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/payroll-run db "emp-1-2026-P01")))
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest unsourced-process-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t3"
                  {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
                   :gross 3692.31M :withholding 812.31M
                   :source {:class :irs-federal-withholding-table :ref "demo"}
                   :unsourced? true}
                  operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))
    (is (nil? (store/payroll-run db "emp-1-2026-P01")))))

(deftest tolerance-breach-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4"
                  {:op :payroll/process :subject "emp-3" :employee-id "emp-3" :period "2026-P01"
                   :gross 4230.77M :withholding 1200.00M
                   :source {:class :state-withholding-table :ref "ny-it-2104:2026"}}
                  operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:tax-withholding-calculation-gate} (-> (store/ledger db) first :basis)))))

(deftest late-filing-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t5"
                  {:op :filing/submit :subject "fil-2026-q1-941" :filing-id "fil-2026-q1-941"
                   :form "941" :period "2026-Q1" :employer-id "er-100"
                   :as-of "2026-05-15" :due-date "2026-04-30"}
                  operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:filing-deadline-gate} (-> (store/ledger db) first :basis)))
    (is (= :pending (:status (store/filing db "fil-2026-q1-941"))))))

(deftest unknown-form-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t5b"
                  {:op :filing/submit :subject "fil-x" :filing-id "fil-x" :form "1099-NEC"
                   :period "2026-Q1" :employer-id "er-100" :as-of "2026-01-15"}
                  operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:filing-deadline-gate} (-> (store/ledger db) first :basis)))))

(deftest on-time-filing-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t6"
                  {:op :filing/submit :subject "fil-2026-q1-941" :filing-id "fil-2026-q1-941"
                   :form "941" :period "2026-Q1" :employer-id "er-100"
                   :as-of "2026-04-15" :due-date "2026-04-30"}
                  operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :submitted (:status (store/filing db "fil-2026-q1-941"))))))

(deftest uncontracted-disclosure-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t7"
                  {:op :disclosure/query :subject "emp-1" :employee-id "emp-1"}
                  {:actor-id "sub-2" :actor-role :employer-subscriber :tenant "tenant-ghost" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis)))))

(deftest over-disclosure-beyond-tier-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t8"
                  {:op :disclosure/query :subject "emp-1" :employee-id "emp-1" :greedy? true}
                  {:actor-id "sub-1" :actor-role :employer-subscriber :tenant "tenant-basic" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis)))))

(deftest clean-disclosure-within-tier-commits-directly
  (let [[_db actor] (fresh)
        res (exec-op actor "t9"
                  {:op :disclosure/query :subject "emp-1" :employee-id "emp-1"}
                  {:actor-id "sub-1" :actor-role :employer-subscriber :tenant "tenant-basic" :phase 3})]
    (is (= :commit (get-in res [:state :disposition])))))

(deftest disputed-employee-process-escalates-then-human-decides
  (let [[db actor] (fresh)
        r1 (exec-op actor "t10"
                 {:op :payroll/process :subject "emp-2" :employee-id "emp-2" :period "2026-P01"
                  :gross 3230.77M :withholding 193.85M
                  :source {:class :state-withholding-table :ref "ca-de-44:2026"}}
                 operator)]
    (is (= :interrupted (:status r1)))
    (is (= :disputed-employee (-> r1 :state :audit last :reason)))
    (testing "approve -> commit"
      (let [r2 (g/run* actor {:approval {:status :approved :by "compliance-1"}}
                       {:thread-id "t10" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :commit (-> (store/ledger db) last :disposition))))))
  (testing "reject -> hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t11"
                  {:op :payroll/process :subject "emp-2" :employee-id "emp-2" :period "2026-P01"
                   :gross 3230.77M :withholding 193.85M
                   :source {:class :state-withholding-table :ref "ca-de-44:2026"}}
                  operator)
          r2 (g/run* actor {:approval {:status :rejected :by "compliance-1"}}
                     {:thread-id "t11" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/payroll-run db "emp-2-2026-P01"))))))

(deftest dispute-request-always-escalates-regardless-of-confidence
  (let [[_db actor] (fresh)
        r1 (exec-op actor "t12"
                 {:op :dispute/request :subject "emp-1" :disputed-field :withholding :claim 800.00M}
                 officer)]
    (is (= :interrupted (:status r1)))
    (is (= :payroll-dispute (-> r1 :state :audit last :reason)))
    (testing "approve -> commit applies the correction"
      (let [r2 (g/run* actor {:approval {:status :approved :by "compliance-1"}}
                       {:thread-id "t12" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))))))

(deftest every-decision-leaves-one-ledger-fact
  (let [[db actor] (fresh)]
    (exec-op actor "a" {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
                        :gross 3692.31M :withholding 812.31M
                        :source {:class :irs-federal-withholding-table :ref "demo"}}
             operator)
    (exec-op actor "b" {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P02"
                        :gross 3692.31M :withholding 812.31M
                        :source {:class :irs-federal-withholding-table :ref "demo"}
                        :unsourced? true}
             operator)
    (is (= 2 (count (store/ledger db)))
        "one commit + one hold, both recorded")))
