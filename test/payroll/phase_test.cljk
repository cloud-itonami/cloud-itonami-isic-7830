(ns payroll.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [payroll.store :as store]
            [payroll.operation :as op]))

(def operator {:actor-id "po-1" :actor-role :payroll-operator})
(def officer  {:actor-id "co-1" :actor-role :compliance-officer})

(def clean-process
  {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
   :gross 3692.31M :withholding 812.31M
   :source {:class :irs-federal-withholding-table :ref "irs-pub-15-t:2026"}})

(def clean-filing
  {:op :filing/submit :subject "fil-2026-q1-941" :filing-id "fil-2026-q1-941"
   :form "941" :period "2026-Q1" :employer-id "er-100"
   :as-of "2026-04-15" :due-date "2026-04-30"})

(def clean-disclosure
  {:op :disclosure/query :subject "emp-1" :employee-id "emp-1"})

(def dispute-req
  {:op :dispute/request :subject "emp-1" :disputed-field :withholding :claim 800.00M})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-process operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))))

(deftest phase0-allows-governed-reads
  (let [[_ res] (run 0 clean-disclosure {:actor-id "sub-1" :actor-role :employer-subscriber :tenant "tenant-basic"})]
    (is (= :commit (get-in res [:state :disposition])))))

(deftest phase1-forces-approval-on-clean-process
  (let [[_ res] (run 1 clean-process operator)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase2-enables-filing-under-approval
  (let [[_ res] (run 2 clean-filing operator)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-process
  (let [[s res] (run 3 clean-process operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 812.31M (:withholding (store/payroll-run s "emp-1-2026-P01"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (out-of-tolerance withholding) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :payroll/process :subject "emp-3" :employee-id "emp-3" :period "2026-P01"
                          :gross 4230.77M :withholding 1200.00M
                          :source {:class :state-withholding-table :ref "ny-it-2104:2026"}}
                       operator)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest dispute-request-never-auto-commits-at-any-phase
  (doseq [ph [0 1 2 3]]
    (let [[_ res] (run ph dispute-req officer)]
      (is (not= :commit (get-in res [:state :disposition]))
          (str "phase " ph " must not auto-commit a dispute")))))
