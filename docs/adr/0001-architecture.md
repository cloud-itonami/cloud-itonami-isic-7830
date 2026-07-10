# ADR-0001: cloud-itonami-isic-7830 — PayrollProcessor-LLM を封じ込めた知能ノードとする給与計算・申告処理アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図の直接の手本)、`cloud-itonami-isic-7820`(temp-staffing、
  同じ HR 系統だが employer-of-record dispatch モデル、本 actor とは業態
  が異なる)

## 課題

ISIC Rev.4 7830「Other human resources provision」は、既に実装済みの
`isic-7810`(一時金/紹介料モデルのあっせん業者)・`isic-7820`(派遣元が
雇用主になる派遣モデル)のいずれとも異なる HR 業態を指す広いコードである。
`cloud-itonami-isic-6311`/`isic-4610` と同じ narrowing の作法で、
**給与計算・申告処理のアウトソーシング**(クライアントが法的雇用主であり
続け、この actor は給与計算・源泉徴収・申告提出のみを代行する)へ具体的に
絞った。

## 決定

### 1. PayrollProcessor-LLM は最下層の1ノードに封じ込め、直接確定/提出させない

> **PayrollProcessor-LLM は、PayrollGovernor が拒否する給与計算確定・
> 申告提出・紛争解決を決して行わない。**

### 2. PayrollGovernor は8チェック(5 HARD + 3 SOFT)

`tax-withholding-calculation-gate`(源泉徴収額の許容乖離チェック)と
`filing-deadline-gate`(申告期限チェック)が、他の cloud-itonami actor に
存在しない domain-unique HARD チェックである。

### 3. R0 の正直なスコープ

出典カタログは実在する3つの連邦/州源泉徴収表参照(IRS Pub 15-T・CA DE
44・NY IT-2104)+ 実在する2つの連邦申告期限(Form 941・W-2)のみ。
`demo-flat-rate-estimate` は governor のトレランスチェック専用の簡易
近似であり、本番の税額計算基盤ではないことを `docs/operator-guide.md`
に明記した。

### 4. Robotics premise: false

給与計算・申告提出は書面/システム上の処理であり、物理的な作動は存在
しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 7830 スロットが実装へ昇格。
- (+) `isic-7810`/`isic-7820` との構造的差異(あっせん/派遣 vs 給与計算
  アウトソーシング)を ADR に明記した。
- (-) R0 出典は3法域のみ、`demo-flat-rate-estimate` は簡易近似であり
  本番の税額計算には使えない(operator が実ブラケット計算に差し替える
  必要がある)。

## 代替案と不採用理由

- **isic-7820(派遣)のスロットを拡張して給与計算も含める**: 派遣モデルは
  雇用主責任を負うが、給与計算アウトソーシングは雇用主責任を負わない
  (クライアントが雇用主のまま)。業態の法的責任構造が根本的に異なる
  ため、別 actor として独立させるのが正確。
