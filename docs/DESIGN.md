# Payroll-Processing Actor Design — PayrollProcessor-LLM as a contained intelligence node

ADP/Gusto/Paychex 級の給与計算・申告処理サービスを、SaaS課金に依存せず OSS
の actor として自前運用するための設計。`cloud-itonami-isic-6311`
(MarketData-LLM を MarketDataGovernor で封じ込めた構図)を、給与データの
ドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか

給与計算・申告提出案の作成は LLM で加速できる。しかし LLM は次の理由で
**確定・提出の最終権限を持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 出典なしに源泉徴収額を確定 | 税務当局への誤申告 |
| 許容乖離を超えた計算ミスをそのまま通す | 過少/過大徴収、罰則リスク |
| 申告期限超過を「期限内提出」として記録 | 申告遅延の隠蔽 |
| 紛争中の従業員への給与計算を高確信のまま自動処理 | 紛争の悪化 |

## 2. OperationActor(`src/payroll/operation.cljk`)

`intake → advise(PayrollProcessor-LLM) → govern(PayrollGovernor) → decide`
— governor-clean なら `commit`、`escalate` なら `request-approval`
([interrupt-before] 経由で人間承認)、hard violation なら `hold`。

## 3. PayrollGovernor(`src/payroll/policy.cljk`)

8チェック(優先順位順、HARDは人間承認でも上書き不可):

1. **rbac**
2. **tax-withholding-calculation-gate**(新規) — 提案された源泉徴収額が
   R0 の簡易フラットレート推定から `tolerance-pct`(20%)を超えて乖離
   したら拒否
3. **filing-deadline-gate**(新規) — 対象 form が R0 filing-catalog に
   無い、または提出時点(`:as-of`)が申告期限(`:due-date`)を過ぎている
   場合は拒否
4. **source-provenance-gate** — 出典クラスが
   `payroll.facts/allowed-source-classes` に無ければ拒否
5. **licensed-disclosure** — 有効な雇用主契約が無い、または開示列が
   tier を超えたら拒否
6. 確信度フロア(SOFT)
7. **disputed-employee-status gate**(SOFT) — 対象従業員が `:disputed`
   なら必ず人間承認
8. **dispute-request**(SOFT無条件) — 給与紛争は常に人間レビュー

## 4. R0 の正直なスコープ

`src/payroll/facts.cljk`: 連邦(IRS Pub 15-T)+ カリフォルニア州(DE 44)
+ ニューヨーク州(IT-2104)の3法域の源泉徴収表参照、および連邦 Form
941(四半期)/W-2(年次)の申告期限のみ。`demo-flat-rate-estimate` は
governor のトレランスチェック専用の簡易近似であり、本番の税額計算基盤
ではない(`docs/operator-guide.md` に明記)。

## 5. Phase 0→3

`default-phase` は実装当初から保守的な `1`(isic-6311 の fail-open 修正
を最初から適用)。`:dispute/request` はどの phase の `:auto` にも入らない。

## 6. テスト(`kbb -M:dev:test`)

`test/payroll/policy_contract_test.cljk` がガバナンス契約を実行可能にする。
`test/payroll/phase_test.cljk` が段階導入を保証。`test/payroll/facts_test.cljk`
が出典カタログの正直さを保証。
