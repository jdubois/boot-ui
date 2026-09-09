---
on:
  schedule: daily
  workflow_dispatch:

permissions:
  contents: read
  issues: read
  pull-requests: read

network:
  allowed:
    - defaults
    - "java"

safe-outputs:
  create-pull-request:
    title-prefix: "[code-simplifier] "
    draft: true
---

# Code Simplifier

Review production code changed in the last 24 hours. Look for one clear opportunity to remove needless branching, duplication, dead local abstractions, or confusing naming while preserving behavior and public interfaces exactly.

Make only a focused change with a measurable readability or maintainability benefit. Run the repository's formatter, tests, linter, and build. Open a draft pull request describing why the result is simpler and which checks passed. Do nothing when no worthwhile simplification exists.
