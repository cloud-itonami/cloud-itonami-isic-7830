# Open Business Blueprint: cloud-itonami-isic-7830

This repository publishes an OSS business model for operating a payroll
processing and administration-as-a-service business (ADP / Gusto / Paychex
class) on itonami.cloud.

## Classification

- Repository name: `cloud-itonami-isic-7830`
- Primary classification: ISIC Rev.4 7830 (Other human resources
  provision), narrowed to payroll processing and administration-as-a-
  service
- Served domain: payroll-run computation, statutory filing submission,
  governed disclosure — never employment decisions or benefits
  eligibility

## Customer

- small/mid-size employers who want payroll processed without building
  in-house tax-withholding infrastructure
- other `cloud-itonami-{ISIC}` blueprint operators who employ contracted
  workers and need payroll processing as a licensed capability

## Problem

Payroll vendors (ADP, Gusto, Paychex) hold this data inside closed systems.
Customers cannot inspect the governance logic (why was this withholding
accepted, why was this filing held), and vendors have no structural
guarantee against a miscalculated withholding or a silently-late filing.

## Offer

- payroll-run computation with cited statutory withholding basis
- filing submission with a real, honest deadline catalog
- governed, tier-scoped disclosure
- a payroll-dispute channel, always human-reviewed
- immutable audit ledger

## Revenue

- per-employee monthly processing fee
- tiered subscriptions: `:tier/basic` (payroll figures) →
  `:tier/employer` (+ source/filing-status detail)
- managed hosting: monthly subscription per employer tenant
- jurisdiction-expansion package: adding a new state's withholding table

## Marketplace Metadata

```edn
{:itonami.blueprint/id "cloud-itonami-isic-7830"
 :itonami.blueprint/name "Payroll Processing & Administration Actor"
 :itonami.blueprint/isic-rev4 "7830"
 :itonami.blueprint/domain :hr/payroll-processing
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-7830"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger]}
```

## Non-Negotiables

- Do not commit real payroll data or real filing credentials.
- Do not add a schema field for employment decisions or benefits
  eligibility.
- Do not bypass the PayrollGovernor for production payroll runs or
  filings.
- Do not fabricate a withholding-table entry or a filing deadline.
