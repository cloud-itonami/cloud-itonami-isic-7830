# cloud-itonami-isic-7830

Open Business Blueprint for **ISIC Rev.4 7830**: other human resources
provision, narrowed to **payroll processing and administration-as-a-
service** — the ADP / Gusto / Paychex class of business — published as an
OSS business that any qualified operator can fork, deploy, run, improve and
sell.

The client remains the legal employer. This actor computes payroll runs
(gross → withholding → net), submits statutory filings, and serves
governed disclosure to the employer — it never makes an employment
decision (hire/fire/discipline) and never determines benefits eligibility.
Distinct from [`cloud-itonami-isic-7810`](https://github.com/cloud-itonami/cloud-itonami-isic-7810)
(one-time placement-fee agency) and
[`cloud-itonami-isic-7820`](https://github.com/cloud-itonami/cloud-itonami-isic-7820)
(temp-staffing employer-of-record dispatch). Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime
— the same actor pattern as
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311).

> **Why an actor layer at all?** A PayrollProcessor-LLM is great at
> normalizing a payroll run and drafting filing submissions — but it has
> **no notion of statutory withholding tolerance, filing deadlines, or a
> disputed employee's frozen status**. Letting it commit directly invites
> a miscalculated withholding (a real tax-authority compliance failure), a
> late filing silently marked as on-time, or a payroll run for an employee
> whose status is actively disputed. This project seals the
> PayrollProcessor-LLM into a single node and wraps it with an independent
> **PayrollGovernor**, a human **review workflow**, and an immutable
> **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **computes and submits payroll only**. It never makes an
employment decision, never determines benefits eligibility, never holds
custody of funds beyond what a payroll processor legally requires (see
`docs/adr/0001-architecture.md`). Withholding provenance is limited to
real, citable authority publications (`src/payroll/facts.cljc`: IRS
Publication 15-T federal + California DE 44 + New York IT-2104, tax year
2026) — every computed withholding must cite one of these, never a bare
"the LLM estimated it".

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌───────────────────┐  proposal    ┌──────────────────────┐
   │ PayrollProcessor-  │ ───────────▶│ PayrollGovernor        │ (independent)
   │ LLM (sealed)       │ draft+source │ withholding-tolerance ·│
   └───────────────────┘              │ deadline · provenance  │
                                       └──────────────────────┘
                                              │
                                   commit / file only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: PayrollProcessor-LLM never commits a payroll run,
submits a filing, or resolves a dispute the PayrollGovernor would reject.

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 7-operation demo through one OperationActor
clojure -M:lint
```

## Non-Negotiables

- Do not commit real payroll data or real filing credentials.
- Do not add a schema field for employment decisions or benefits
  eligibility.
- Do not bypass the PayrollGovernor for production payroll runs or
  filings.
- Do not serve a disclosure without an active, registered employer
  contract.
- Do not fabricate a withholding-table entry or a filing deadline.

License: AGPL-3.0-or-later.
