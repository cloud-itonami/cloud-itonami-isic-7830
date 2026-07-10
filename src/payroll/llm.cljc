(ns payroll.llm
  "PayrollProcessor-LLM client — the *contained intelligence node*.

  It normalizes a payroll run (gross → withholding → net) from the
  employee's jurisdiction and salary, drafts filing-submission proposals,
  proposes disclosure column sets, and drafts dispute-resolution notes.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*,
  never a committed payroll run or filed return. Every output is censored
  downstream by `payroll.policy` (the PayrollGovernor) before anything
  touches the SSoT or is filed with a tax authority.

  Deterministic mock, mirrors `marketdata.llm`'s discipline."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [payroll.store :as store]))

(defn- propose-process
  "Payroll-run computation. `:unsourced?` injects the failure mode we must
  defend against: a withholding figure with no citation to a real
  withholding-table class."
  [_db {:keys [employee-id period gross withholding source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "payroll run: " employee-id " period " period)
     :rationale "R0出典テーブルに基づく源泉徴収額の正規化。"
     :cites     [:employee-id :period :gross :withholding]
     :source    src
     :effect    :payroll-commit
     :value     {:id (str employee-id "-" period) :employee-id employee-id
                 :period period :gross gross :withholding withholding
                 :net (- gross withholding) :source src}
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-filing
  "Filing-submission draft. `as-of`/`due-date` are supplied by the caller;
  the LLM's role is limited to citing the R0 filing-catalog basis."
  [_db {:keys [filing-id form period employer-id as-of due-date]}]
  {:summary   (str "filing submit: " form " " period)
   :rationale "R0 filing-catalog に基づく期限確認済みの提出案。"
   :cites     [:form :period :as-of]
   :source    nil
   :effect    :filing-submit
   :value     {:id filing-id :form form :period period :employer-id employer-id
               :status :submitted :submitted-at as-of}
   :as-of     as-of
   :due-date  due-date
   :form      form
   :confidence 0.9})

(defn- propose-disclosure
  [_db {:keys [employee-id greedy?]}]
  (let [base [:employee-id :period :gross :withholding :net]
        greedy-extra [:source :filing-status]]
    {:summary   (str "開示列提案: " employee-id)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-dispute
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "payroll の " disputed-field " について訂正申立てへの解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :correction-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  [db {:keys [op] :as request}]
  (case op
    :payroll/process     (propose-process db request)
    :filing/submit       (propose-filing db request)
    :disclosure/query    (propose-disclosure db request)
    :dispute/request     (propose-dispute db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは給与計算・申告処理のアドバイザーです。与えられた事実のみに"
       "基づき、提案を1つだけ EDN マップで返します。説明や前置きは一切書かず、"
       "EDN だけを出力します。\n"
       "キー: :summary :rationale :cites :source(nilか{:class :jurisdiction}) "
       ":effect(:payroll-commit|:filing-submit|:disclosure-serve|"
       ":correction-apply) :value :confidence(0..1)。\n"
       "重要: 出典を伴わない源泉徴収額は絶対に提案してはいけません。"))

(defn- facts-for [st {:keys [op subject employee-id]}]
  (case op
    :payroll/process {:employee (store/employee st (or employee-id subject))}
    {:employee (store/employee st (or employee-id subject))}))

(defn- parse-proposal
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  [request proposal]
  {:t          :payrollllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
