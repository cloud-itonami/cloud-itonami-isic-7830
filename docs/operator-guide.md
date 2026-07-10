# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-7830`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-7830
cd cloud-itonami-isic-7830
clojure -M:dev:test
clojure -M:dev:run
```

## 2. Production Checklist

- replace `payroll.facts/demo-flat-rate-estimate` with a real bracket-based
  withholding calculator sourced from the real publications already cited
  in `withholding-catalog` — the demo flat-rate map is ONLY a tolerance-
  check approximation, never a production calculation basis
- extend `withholding-catalog`/`filing-catalog` honestly for every
  jurisdiction you serve — never fabricate a rate or deadline
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret
  manager
- define employer contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test` and `clojure -M:lint`
- verify audit-ledger export
- document backup/restore and incident response
- document the payroll-dispute-handling SLA
- get written legal/tax review for every jurisdiction you serve

## 3. Operator Responsibilities

Operators are responsible for lawful basis and licensing for payroll
processing in each jurisdiction served, secure infrastructure and tenant
isolation, honest withholding/filing-catalog maintenance, human review for
disputed-employee and dispute-request operations, data-retention policy,
and security updates.
