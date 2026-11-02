---
date: 2026-11-02
summary: >
  Comments, the README and current docs no longer name the private app built on fuseki; the
  example dataset name is `my-app`. No behaviour change, no changeset.
packages: [fuseki]
---

# 006 — No host-app mentions

## PR

**What changed.** The private host app's name, its gateway, plans and architecture docs are gone
from `src/` (comments in `FusekiStore.ts` and `FusekiStore.errors.test.ts`), from `README.md`
(the example dataset `cn-main` is now `my-app`, also in `utils/datasets.test.ts`) and from
`docs/backlog/001`, `006` and `docs/plans/001`, `002`. Links to the host's plans were dropped and
their rules stated inline. **Verification:** `npx linked build` (`✔ Build successful`), `npm test`
(`Test Suites: 1 skipped, 4 passed, 4 of 5 total`, `Tests: 5 skipped, 21 passed, 26 total`).
**Deviations:** none. **Follow-ups:** none.

## Details

Comments, docs and one test's example dataset name only.
