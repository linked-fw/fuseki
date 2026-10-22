---
summary: >
  Low findings of the independent reviews of the store-inference module and image (report 001)
  that were not fixed: read-committed auto-promotion bypasses the hierarchy check (embedded
  callers only); a failed write outside a transaction leaves its thread marked; no tests for
  `dbType=mem` staying plain or for a reader that began mid-commit; dataset `stream()` does not
  infer; the Maven build stage and the workflow actions are pinned by tag, not digest.
status: Open
---

# 001 — Store inference: review lows not yet fixed

From the reviews recorded in [report 001](../reports/001-store-inference.md) (CN plan 070). None
affects the image's HTTP endpoints as served today; each is a correctness edge for embedded
callers, a test gap or supply-chain hardening.

## 1. Read-committed auto-promotion bypasses `afterPromote`

`DatasetGraphRDFSLive` re-resolves a transaction's view when it is promoted through
`promote()`/`promote(Promote)` (`afterPromote`), because a read-committed promotion moves the
transaction to newer data. TDB2 also promotes a `READ_COMMITTED_PROMOTE` transaction by itself on
its first write, without calling the wrapper's `promote`, so such a transaction keeps the view it
resolved before the promotion and may read with a hierarchy older than the data it now sees.
It also never refreshes the transaction's `started` count, so even a transaction that writes
first (auto-promoting) and then reads sees the newer data with the older hierarchy (reproduced:
another thread commits `:a rdfs:subClassOf :b . :x a :a`; the promoted transaction sees `:x a :a`
but not `:x a :b`). Fuseki's own endpoints begin `READ` or `WRITE` transactions, so only embedded
callers that begin `READ_COMMITTED_PROMOTE` are affected.

First question: detect the mode change at the next read (compare `transactionMode()` and
`transactionType()` against what `TxnState` recorded) or refuse `READ_COMMITTED_PROMOTE`?

## 2. A failed write outside a transaction leaves its thread marked

`SubClassOfTracker.check`/`TrackingGraph.check` call `touch()` before `super.add`/`delete`. When a
write outside a transaction fails (TDB2 refuses it), the thread's mark stays set, and that
thread's next transaction commits as a hierarchy change: one needless rebuild at the next read.
Harmless for correctness; it costs one rebuild.

First question: clear the mark when the delegated write throws, or mark only after it returns
(the mark must still be set before a commit can observe the write)?

## 3. Missing tests

- **`dbType=mem` stays plain:** no test creates a dataset with `dbType=mem` through the admin API
  (`config-mem`) and checks that it does not infer (the contract says only `dbType=tdb2` datasets
  infer). `AdminTemplateTest` covers `tdb2` only.
- **A reader that began mid-commit:** the two counters (`generation` after TDB2's commit,
  `pending` before it) make a transaction that begins while a hierarchy commit is in flight build
  its own view. The writer-that-waited case has a test; the reader that begins between `pending`
  and `generation` has none (it needs a hook to pause a commit inside `stored.commit()`).

- **Private builds:** the GSP tests count `rebuilds()`, which leaves out the private
  views a transaction builds after its own hierarchy write; a `builds()` counter including them
  would make "no resolution inside a GSP write" airtight.

## 4. Dataset-level `stream()` does not infer

`DatasetGraphRDFSLive.stream()` and `stream(g, s, p, o)` miss inferred quads, in read and write
transactions alike: Jena's `DatasetGraphWithGraphTransform` does not override them, so they reach
the stored dataset, while `find` infers. ARQ and Fuseki read with `find`, so no endpoint is
affected; an embedded caller using `stream` is.

First question: route `stream` to `find` in the live dataset, or report it upstream to Jena?

## 5. Supply-chain pinning

- `server/Dockerfile`'s build stage is `maven:3.9-eclipse-temurin-21` by tag; the runtime stage
  is pinned by digest. A digest for the build stage (Renovate can keep it current) makes the
  image build reproducible.
- `.github/workflows/image.yml` uses actions by major tag (`actions/checkout@v4`,
  `docker/build-push-action@v6`, …). The job has `packages: write`; pinning each action to a
  commit SHA (with the tag in a comment, Renovate-maintained) closes the tag-move risk.
