(ns payroll.store-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [payroll.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "出島貿易株式会社(デモ)" (:name (store/employer s "er-100"))))
      (is (= "Jane Doe (demo)" (:name (store/employee s "emp-1"))))
      (is (= :active (:status (store/employee s "emp-1"))))
      (is (= :disputed (:status (store/employee s "emp-2"))))
      (is (= 3 (count (store/employees-of s "er-100"))))
      (is (= :pending (:status (store/filing s "fil-2026-q1-941"))))
      (is (= :tier/employer (:tier (store/contract s "tenant-er-100")))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "payroll-commit writes a new run"
        (store/commit-record! s {:effect :payroll-commit
                                 :value {:id "emp-1-2026-P01" :employee-id "emp-1" :period "2026-P01"
                                         :gross 3692.31M :withholding 812.31M :net 2880.00M
                                         :source {:class :irs-federal-withholding-table :ref "demo"}}})
        (is (= 812.31M (:withholding (store/payroll-run s "emp-1-2026-P01")))))
      (testing "filing-submit marks a filing submitted"
        (store/commit-record! s {:effect :filing-submit
                                 :value {:id "fil-2026-q1-941" :status :submitted :submitted-at "2026-04-15"}})
        (is (= :submitted (:status (store/filing s "fil-2026-q1-941")))))
      (testing "correction-apply patches the target payroll run"
        (store/commit-record! s {:effect :correction-apply
                                 :value {:patch {:withholding 800.00M}}
                                 :path ["emp-1-2026-P01"]})
        (is (= 800.00M (:withholding (store/payroll-run s "emp-1-2026-P01")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/employer s "nope")))
    (is (= [] (store/ledger s)))
    (store/with-employers s {"x" {:id "x" :name "X" :jurisdiction :us-federal}})
    (is (= "X" (:name (store/employer s "x"))))))
