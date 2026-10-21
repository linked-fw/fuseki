---
summary: >
  The Fuseki module and image of CN plan 070 (step F1): `lf:DatasetRDFSLive` (Jena's query-time
  RDFS view over TDB2, its class hierarchy rebuilt lazily from the stored `rdfs:subClassOf`
  triples), the `/<ds>/stored` endpoint, SERVICE off, the inferring `config-tdb2` template and `ds`
  dataset, the delete filter and template guard; measurements M1-M13 that settle the pending
  contract items; the `storedEndpoint` helper.
packages: [fuseki]
---

# 001 — Store inference: the RDFS module, the image and the stored endpoints

Source: sub-plan [001](../plans/001-store-inference.md), CN plan 070 step F1 (sensitive).

## What was built

(Phase 3 completes this section.)

## Measurements (M1-M13)

Phase 2, on the built image `linked-fuseki:dev` (module `6.2.0-1`), compose project `f1-probe`,
ports 3234/3235/3232. `server/probe/probe.sh`, output quoted as printed (2026-10-21).

(Phase 3 inserts the final run.)
