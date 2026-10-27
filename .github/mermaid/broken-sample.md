# Intentionally broken Mermaid sample

Fixture for the `mermaid-validate` CI self-test. The diagram below is invalid on purpose:
`scripts/validate-mermaid.sh .github/mermaid/broken-sample.md` must exit 1. The normal
repository scan excludes `.github/mermaid/`.

```mermaid
flowchart TD
    A[Start] --> --> B
    B --> C(
```
