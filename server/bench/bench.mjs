// The ~1M-triple benchmark: lf:DatasetRDFSLive vs Jena's stock ja:DatasetRDFS vs plain TDB2.
// Usage: BENCH_URL=http://localhost:3232 node bench.mjs   (services from conf/bench.ttl)
// Each query: one cold run, then 10 timed runs (median, p90).
const BASE = process.env.BENCH_URL ?? 'http://localhost:3232';
const S = 'http://schema.org/';
const P = `PREFIX schema: <${S}> PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> `;
const INST = '<http://example.org/d/photograph/24762>';
const Q = [
  ['Q1 count Thing', `SELECT (COUNT(DISTINCT ?s) AS ?c) { ?s a schema:Thing }`],
  ['Q2a count CreativeWork', `SELECT (COUNT(DISTINCT ?s) AS ?c) { ?s a schema:CreativeWork }`],
  ['Q2b count Photograph', `SELECT (COUNT(DISTINCT ?s) AS ?c) { ?s a schema:Photograph }`],
  ['Q3a page CW off 0', `SELECT ?s ?name { ?s a schema:CreativeWork ; schema:name ?name } ORDER BY ?s LIMIT 50 OFFSET 0`],
  ['Q3b page CW off 5000', `SELECT ?s ?name { ?s a schema:CreativeWork ; schema:name ?name } ORDER BY ?s LIMIT 50 OFFSET 5000`],
  ['Q4 Event select', `SELECT ?s ?name ?ln ?img { ?s a schema:Event ; schema:name ?name . OPTIONAL { ?s schema:location ?l . ?l schema:name ?ln } OPTIONAL { ?s schema:image ?i . ?i schema:contentUrl ?img } } LIMIT 100`],
  ['Q5 ASK inst a Thing', `ASK { ${INST} a schema:Thing }`],
  ['Q6 inst ?p ?o', `SELECT ?p ?o { ${INST} ?p ?o }`],
  ['Q7 owned walk', `SELECT ?x ?p ?o { ?s a schema:CreativeWork . ?s schema:image ?x . ?x ?p ?o } LIMIT 1000`],
  ['Q9a GRAPH ?g Thing', `SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s a schema:Thing } }`],
];
// BENCH_SERVICES reorders or narrows the services, e.g. bench-stock,bench,bench-plain.
const SERVICES = (process.env.BENCH_SERVICES ?? 'bench,bench-stock,bench-plain').split(',');
async function run(svc, q) {
  const t = performance.now();
  const r = await fetch(`${BASE}/${svc}/query`, { method: 'POST', headers: { 'Content-Type': 'application/sparql-query', Accept: 'application/sparql-results+json' }, body: P + q });
  if (!r.ok) throw new Error(svc + ' ' + r.status + ' ' + await r.text());
  const j = await r.json();
  const ms = performance.now() - t;
  let n;
  if ('boolean' in j) n = j.boolean;
  else if (j.results.bindings.length === 1 && j.results.bindings[0].c) n = 'c=' + j.results.bindings[0].c.value;
  else n = 'rows=' + j.results.bindings.length;
  return { ms, n, j };
}
async function upd(svc, u) {
  const t = performance.now();
  const r = await fetch(`${BASE}/${svc}/update`, { method: 'POST', headers: { 'Content-Type': 'application/sparql-update' }, body: P + u });
  if (!r.ok) throw new Error(svc + ' ' + r.status + ' ' + await r.text());
  return performance.now() - t;
}
const stat = a => { const s = [...a].sort((x, y) => x - y); return { med: s[Math.floor(s.length / 2)], p90: s[Math.ceil(s.length * 0.9) - 1] }; };
const f = x => x.toFixed(1);
async function measure(label, svc, q) {
  const cold = await run(svc, q); const w = [];
  let n;
  for (let i = 0; i < 10; i++) { const r = await run(svc, q); w.push(r.ms); n = r.n; }
  const { med, p90 } = stat(w);
  console.log([label, svc, 'cold', f(cold.ms), 'med', f(med), 'p90', f(p90), n].join('\t'));
}
for (const [label, q] of Q) for (const svc of SERVICES) await measure(label, svc, q);

const X = '<http://example.org/d/photograph/new1>';
const data = `${X} a schema:Photograph ; schema:name "new" ; schema:description "d" ; schema:url "https://x" ; schema:keywords "k" .`;
for (const svc of SERVICES) {
  const ins = [], del = [];
  for (let i = 0; i < 11; i++) { ins.push(await upd(svc, `INSERT DATA { ${data} }`)); del.push(await upd(svc, `DELETE DATA { ${data} }`)); }
  const a = stat(ins.slice(1)), b = stat(del.slice(1));
  console.log(['Q8 INSERT DATA', svc, 'cold', f(ins[0]), 'med', f(a.med), 'p90', f(a.p90)].join('\t'));
  console.log(['Q8 DELETE DATA', svc, 'cold', f(del[0]), 'med', f(b.med), 'p90', f(b.p90)].join('\t'));
}

// A hierarchy change mid-run: a new subclass of Thing with one instance.
const N = '<http://example.org/NewKind>', NI = '<http://example.org/d/newkind/1>';
await upd('bench', `INSERT DATA { ${N} rdfs:subClassOf schema:Thing . ${NI} a ${N} }`);
let r = await run('bench', `ASK { ${NI} a schema:Thing }`);
console.log(['H1 after subClassOf insert: ASK new instance a Thing', 'bench', 'first query ms', f(r.ms), r.n].join('\t'));
r = await run('bench', `SELECT (COUNT(DISTINCT ?s) AS ?c) { ?s a schema:Thing }`);
console.log(['H2 count Thing after the change', 'bench', 'ms', f(r.ms), r.n].join('\t'));
await upd('bench', `DELETE DATA { ${N} rdfs:subClassOf schema:Thing . ${NI} a ${N} }`);
r = await run('bench', `ASK { ${NI} a schema:Thing }`);
console.log(['H3 after delete: ASK', 'bench', 'first query ms', f(r.ms), r.n].join('\t'));
