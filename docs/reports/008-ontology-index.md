---
date: 2026-11-10
summary: >
  The ontology is registered by src/ontologies/index.ts (registerOntologies) and the package registers no prefix; an app's prefix map holds the prefixes. Major.
packages: [fuseki]
---

# 008 — The ontology index; no prefix registered

## PR

**What changed.** The package's ontology is registered by one generated module,
`src/ontologies/index.ts`, which calls core's `registerOntologies([...], import.meta.url)` with the
ontology's name, namespace, terms, data files and the prefix its data suggests. The package entry
imports it (and `shapes/index` does, where the package has one). `src/package.ts` exports `registerOntologies` in place of
`linkedOntology`. The package adds no prefix to core's prefix map any more: an app loads its own
map (`linked.app.json`), and the ontology's data states the prefix it suggests.

`ontologies/fuseki.register` is removed, with the deprecated `lincd-fuseki` prefix it added; `ontologies/fuseki` keeps `ns`, `_self`, `FusekiStore` and `fuseki` and drops `dataFiles` and `loadData`. The test of the register module (it read `_prefix` and the registered prefixes) is now `ontologies/index.test.ts`: the index registers the ontology by name with its suggested prefix, adds no prefix, and a map that has `fuseki` compacts and expands its terms.

Changeset: major (`.changeset/ontology-index.md`), released with the core major that provides
`registerOntologies`.

**Verification.**

- `linked generate-ontology --check`:

  ```
  fuseki: registered by the index, vann data checked)
  Every ontology module is current.
  ```

- `linked build`: `✔ Build successful`.
- `npm test`:

  ```
  Test Suites: 1 skipped, 4 passed, 4 of 5 total
  Tests:       5 skipped, 21 passed, 26 total
  ```

- No `linkedOntology`, `Prefix.add` or `.register.js` left in `src/` (grep, empty).

**Deviations.** None.

**Follow-ups.** None in this repo.

## Details

Built and tested against the core and cli of the same coordinated major, and the standard
vocabulary packages in the index layout. The same change across every linked package with an
ontology is described alike in each package's report.
