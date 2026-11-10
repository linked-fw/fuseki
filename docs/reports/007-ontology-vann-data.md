---
date: 2026-11-10
summary: >
  The ontology's data carries its suggested prefix and namespace as vann triples; package.json declares the hand-written ontology with "generated": false. Patch.
packages: [fuseki]
---

# 007 — Ontology vann data

## PR

**What changed.** The `owl:Ontology` node in `src/data/fuseki.ttl` states
`vann:preferredNamespacePrefix "fuseki"` and `vann:preferredNamespaceUri "https://linked.cm/ont/fuseki/"`,
the prefix this ontology registers today, so Turtle output does not change. `package.json` declares the ontology: `"linkedOntologies": {"fuseki": {"namespace": "https://linked.cm/ont/fuseki/", "generated": false}}`. The module stays hand-written (it exports more than the generator writes); the build checks its vann data and generates nothing. No import path
or export changed; the deprecated `lincd-` prefix label is untouched. Changeset: patch.

**Verification.**

- `linked generate-ontology --check` (exit 0):

  ```
  fuseki: 1 terms from src/data/fuseki.ttl (modules written by hand: vann data checked, nothing generated)
  Every ontology module is current.
  ```

- `linked build`: `✔ Build successful`.
- `npm test`: `Tests: 5 skipped, 21 passed, 26 total`.

**Deviations.** None.

**Follow-ups.** `linkedOntology` (in `@_linked/core`) still registers the name as a prefix; its removal is
recorded in core's backlog 117.

## Details

The same change across the linked vocabularies, with all their verification outputs, is in
`@_linked/core`'s report 118 (linked vocabularies: vann data and one declaration).
