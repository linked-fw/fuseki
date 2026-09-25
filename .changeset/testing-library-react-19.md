---
'@_linked/fuseki': patch
---

Test tooling: `@testing-library/react` moves from v14 to v16.3.3.

v14 peers on React 18 while the package develops against React 19, so npm could
not resolve the dependency tree at all — installs here required
`--legacy-peer-deps`, and the committed lockfile had been written under that
relaxation (React 19.3.0 and testing-library 14.3.1 side by side). v16.1.0 is
the first release whose peers accept React 19; we take the current 16.3.3, which
is what `@_linked/react` already uses. The lockfile now resolves with plain
`npm install`. No published code changes — devDependency only.
