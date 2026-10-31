---
summary: >
  When the module enforces connection rights, it must enforce the shapeless delete's rule too: a
  delete of whole nodes in every graph (core's `Shape.delete(ids)`) needs the connection's
  `access:shapelessDelete` flag and `write` on every shape. Today only Create Now's gateway checks it.
status: Open
---

# 006 — Enforce the shapeless delete's rule in the store

## Context

Core's shapeless delete (`Shape.delete(ids)` on the base class, IR `delete_shapeless`) deletes
every triple of its subjects, and the links to them, in every graph of the dataset, the default
graph included. It is one SPARQL update with a default-graph side and a `GRAPH ?g` side over every
named graph (`deleteShapelessToSparql` in `@_linked/core/sparql`).

Create Now gates it at its gateway (CN arch-07 §App queries at CN's gateway, arch-04 §Connection
rights and enforcement), and needs both:

- the app's right: its connection carries `access:shapelessDelete` (`https://linked.cm/ont/access/shapelessDelete`,
  xsd:boolean, off by default);
- the user's right: the level through the connection is at least `write` on every shape.

The store is meant to check the same connection again (CN uploads each connection's rights to its
rights file), so an app that reaches the store directly is held to the same rights. The module does
not enforce connection rights yet, so it does not check this rule either.

## What to do

- Carry the flag in the rights file entry CN writes per connection.
- Recognise the shapeless delete's update shape (no type pattern ties its subjects to a class) and
  refuse it for a connection without the flag, or whose default level or any per-shape level is
  below `write`.
- Tests: the update refused without the flag and with one shape below `write`; allowed otherwise.

## Open questions

- Should the module refuse any update whose subjects are not tied to a type, unless the flag is set,
  rather than matching the exact shape core generates?
