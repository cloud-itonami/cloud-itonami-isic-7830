(ns payroll.llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [payroll.store :as store]
            [payroll.llm :as llm]))

(deftest process-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
                         :gross 3692.31M :withholding 812.31M
                         :source {:class :irs-federal-withholding-table :ref "demo"}})]
    (is (= :payroll-commit (:effect p)))
    (is (= {:class :irs-federal-withholding-table :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-process-proposal-carries-nil-source
  (testing "the LLM layer does not filter -- that is the governor's job"
    (let [db (store/seed-db)
          p (llm/infer db {:op :payroll/process :subject "emp-1" :employee-id "emp-1" :period "2026-P01"
                           :gross 3692.31M :withholding 812.31M
                           :source {:class :irs-federal-withholding-table :ref "demo"}
                           :unsourced? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85) "still high-confidence -- proves source-provenance cannot rely on confidence"))))

(deftest filing-proposal-carries-as-of-and-due-date
  (let [db (store/seed-db)
        p (llm/infer db {:op :filing/submit :subject "fil-1" :filing-id "fil-1" :form "941"
                         :period "2026-Q1" :employer-id "er-100"
                         :as-of "2026-04-15" :due-date "2026-04-30"})]
    (is (= :filing-submit (:effect p)))
    (is (= "2026-04-15" (:as-of p)))
    (is (= "2026-04-30" (:due-date p)))))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :disclosure/query :subject "emp-1" :employee-id "emp-1"})
        greedy (llm/infer db {:op :disclosure/query :subject "emp-1" :employee-id "emp-1" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :dispute/request :subject "emp-1" :disputed-field :withholding :claim 800.00M})]
    (is (= :correction-apply (:effect p)))
    (is (< (:confidence p) 0.9))))
