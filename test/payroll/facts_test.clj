(ns payroll.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [payroll.facts :as facts]))

(deftest withholding-catalog-entries-are-well-formed
  (doseq [{:keys [id name jurisdiction class tax-year url]} facts/withholding-catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? jurisdiction))
      (is (keyword? class))
      (is (number? tax-year))
      (is (string? url)))))

(deftest allowed-source-classes-matches-catalog
  (is (= (into #{} (map :class facts/withholding-catalog)) facts/allowed-source-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :irs-federal-withholding-table))
  (is (facts/class-allowed? :state-withholding-table))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? :estimated)))
  (is (not (facts/class-allowed? nil))))

(deftest filing-catalog-has-federal-941-and-w2
  (is (facts/filing-for "941"))
  (is (facts/filing-for "W-2"))
  (is (nil? (facts/filing-for "1099-NEC"))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= (count facts/withholding-catalog) (:withholding-source-count c)))
    (is (<= (:withholding-source-count c) 20) "R0 catalog should stay small and citable")
    (is (contains? (:withholding-jurisdictions c) :us-federal))
    (is (contains? (:filing-forms c) "941"))))
