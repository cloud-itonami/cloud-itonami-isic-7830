(ns payroll.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the PayrollGovernor's licensed-disclosure
  gate approved for the caller's contract tier."
  (:require [payroll.store :as store]))

(defn render-payroll
  [db employee-id period columns]
  (let [run (store/payroll-run db (str employee-id "-" period))
        emp (store/employee db employee-id)
        cell (fn [col]
               (case col
                 :employee-id employee-id
                 :period (:period run)
                 :gross (:gross run)
                 :withholding (:withholding run)
                 :net (:net run)
                 :source (:source run)
                 :filing-status (:status emp)
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
