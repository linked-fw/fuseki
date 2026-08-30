---
'@_linked/fuseki': major
---

A failed Fuseki request now throws `FusekiQueryError` instead of being swallowed.

`executeSparqlSelect` returned an empty result set on any non-JSON response — the comment read
"so callers don't crash" — and never checked the HTTP status at all. So a 404, a 500, an HTML
error page and a malformed query were indistinguishable from "no rows": every read through this
store answered "nothing found" when the store was broken.

`executeSparqlUpdate` checked the status but only `console.warn`ed, so a write that never landed
reported success.

**Breaking:** callers that relied on a failed query resolving to an empty result set will now see
a rejection. That is the point — the old behaviour is why a Create Now existence check reported
`false` unconditionally in a running backend, sending every re-save down the `create` branch
instead of `update`, unnoticed.

`FusekiQueryError` carries `endpoint`, `status`, `body` and `sparql`. The body is where the
diagnosis usually is: a SPARQL parse error names its line and column.
