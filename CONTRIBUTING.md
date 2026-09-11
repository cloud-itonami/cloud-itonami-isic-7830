# Contributing

`cloud-itonami-isic-7830` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
kbb -M:dev:test
kbb -M:lint
```

## Rules

- Do not commit real payroll data, real employee records, or real filing
  credentials.
- Keep production payroll runs and filings behind PayrollGovernor.
- Treat every new withholding jurisdiction as high-risk: add tests for
  tax-withholding-calculation-gate, filing-deadline-gate,
  source-provenance-gate, and audit logging.
- Never fabricate a withholding-table entry or a filing deadline — extend
  `payroll.facts` only with real, citable authority publications.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
