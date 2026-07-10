# Governance

`cloud-itonami-isic-7830` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- PayrollProcessor-LLM cannot directly commit a payroll run, submit a
  filing, or resolve a dispute.
- PayrollGovernor remains independent of the advisor.
- hard governor violations (tax-withholding-calculation-gate,
  filing-deadline-gate, source-provenance-gate, licensed-disclosure) cannot
  be overridden by human approval.
- a payroll dispute never auto-resolves, at any rollout phase.
- a payroll run targeting a disputed employee always reaches a human.
- every commit, hold and disclosure event is auditable.
- no schema field exists for employment decisions (hire/fire/discipline)
  or benefits-eligibility determination — this actor only computes and
  submits payroll, it never makes an employment-status decision.
- real payroll data and real filing credentials stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing payroll data to an uncontracted party
- submitting a filing after its statutory deadline without human
  acknowledgment
- computing withholding without a cited real withholding-table source
- misrepresenting certification status
- failing to respond to security incidents or payroll disputes
