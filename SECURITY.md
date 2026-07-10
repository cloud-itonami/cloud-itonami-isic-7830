# Security Policy

This project computes payroll (wages, tax withholding) and submits tax
filings. Treat vulnerabilities as potentially high impact even when the
demo data is synthetic — a compromised withholding calculation or a missed
filing deadline has direct financial and regulatory consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- PayrollGovernor bypass (tax-withholding-calculation-gate,
  filing-deadline-gate, source-provenance-gate, licensed-disclosure)
- audit-ledger tampering
- over-disclosure beyond an employer contract's tier
- tenant isolation failures
- a payroll run committing without a cited real withholding-table source
- a filing marked submitted without a real submission

## Reporting

Use GitHub private vulnerability reporting when available. If unavailable,
contact the repository maintainers through the cloud-itonami organization
before publishing details.

## Production Guidance

- Store secrets outside Git.
- Keep real payroll/employee data outside this repository.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for payroll operators and service accounts.
