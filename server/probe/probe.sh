#!/usr/bin/env bash
# Measurements M1-M13 on the built image. One result line per item: "Mx <name>: <result>".
# LF_ONLY_BENCH=1 runs M10 alone; LF_BENCH_REVERSED=1 adds a second pass in reverse service order.
# Compose project f1-probe (override: LF_PROJECT), ports 3234/3235 (and 3232 for M10). Never 3030.
# M10 needs the benchmark data (server/bench/gen.py) in $LF_BENCH_DATA; it is skipped without it.
set -uo pipefail
cd "$(dirname "$0")"
PROJECT="${LF_PROJECT:-f1-probe}"
PORT="${LF_PROBE_PORT:-3234}"
GPORT="${LF_GUARD_PORT:-3235}"
URL="http://localhost:$PORT"
AUTH=(-u admin:probe)
PREFIX='PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> PREFIX : <http://example.org/> PREFIX schema: <http://schema.org/>'
DC=(docker compose -p "$PROJECT" -f compose.yml)
WORK=$(mktemp -d)
export LF_STOCK_TEMPLATE="$WORK/stock-config-tdb2"
export LF_BENCH_DIR="$WORK/bench"
mkdir -p "$LF_BENCH_DIR" && chmod 777 "$LF_BENCH_DIR"
touch "$LF_STOCK_TEMPLATE"

down() { "${DC[@]}" --profile guard --profile bench down -t 5 >/dev/null 2>&1 || true; }
trap 'down; rm -rf "$WORK"' EXIT

ask() { curl -s -H 'Accept: application/sparql-results+json' --data-urlencode "query=$PREFIX $2" "$1" | grep -o '"boolean" *: *[a-z]*' | grep -o '[a-z]*$'; }
count() { curl -s -H 'Accept: text/csv' --data-urlencode "query=$PREFIX $2" "$1" | tail -1 | tr -d '\r'; }
upd() { curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/sparql-update' --data-binary "$PREFIX $2" "$1"; }
mkds() { curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" --data "dbName=$1&dbType=tdb2" "$URL/\$/datasets"; }
nq() { curl -s -H 'Accept: application/n-quads' "$1" | grep -c . ; }
ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
logs() { "${DC[@]}" logs --no-color "${1:-fuseki}" 2>&1; }
up() { "${DC[@]}" up -d --wait "$@" >/dev/null 2>&1 || { echo "compose up failed"; "${DC[@]}" logs --tail 50; exit 1; }; }
task_wait() { # task id -> waits until finished
  for _ in $(seq 1 120); do
    curl -s "${AUTH[@]}" "$URL/\$/tasks/$1" | grep -q '"finished"' && return 0; sleep 0.5
  done; return 1; }

if [ -z "${LF_ONLY_BENCH:-}" ]; then
down; up fuseki
echo "image: ${LF_IMAGE:-linked-fuseki:dev}; module: $(logs | grep -o '\[linked-fuseki\] module [^,]*' | head -1)"

# M1 template dataset infers
c=$(mkds m1)
info=$(curl -s "${AUTH[@]}" -H 'Accept: application/json' "$URL/\$/datasets/m1" | python3 -c 'import json,sys; print(" ".join(sorted(s["srv.type"]+"="+",".join(repr(e) for e in s["srv.endpoints"]) for s in json.load(sys.stdin)["ds.services"])))')
upd "$URL/m1/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A . GRAPH :g { :b a :A } }' >/dev/null
echo "M1 template dataset infers: create $c; /m1/sparql ASK a⊑B $(ask "$URL/m1/sparql" 'ASK { :a a :B }'); /m1/stored $(ask "$URL/m1/stored" 'ASK { :a a :B }'); endpoints $info"

# M2 stored request forms
r1=$(nq "$URL/m1/stored")
r2=$(curl -s -H 'Accept: application/n-triples' "$URL/m1/stored?default" | grep -c .)
r3=$(curl -s -H 'Accept: application/n-triples' "$URL/m1/stored?graph=http%3A%2F%2Fexample.org%2Fg" | grep -c .)
r4=$(ask "$URL/m1/stored" 'ASK { :a a :A }')
r5=$(curl -s -H 'Content-Type: application/sparql-query' -H 'Accept: application/sparql-results+json' --data-binary "$PREFIX ASK { :a a :B }" "$URL/m1/stored" | grep -o 'true\|false')
r6=$(curl -s -H 'Accept: application/sparql-results+json' --data-urlencode "query=$PREFIX ASK { :a a :A }" -X POST "$URL/m1/stored" | grep -o 'true\|false')
r7=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/n-triples' --data-binary '<http://e/s> <http://e/p> <http://e/o> .' "$URL/m1/stored?default")
r8=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/n-triples' --data-binary '<http://e/s> <http://e/p> <http://e/o> .' "$URL/m1/stored?default")
r9=$(upd "$URL/m1/stored" 'INSERT DATA { :x :p :y }')
echo "M2 stored forms: GET quads $r1 (stored 3); ?default $r2; ?graph= $r3; ?query= ASK stored $r4; POST sparql-query ASK inferred $r5; POST form query= $r6; POST GSP $r7; PUT GSP $r8; sparql-update POST $r9"

# M3 backup
resp=$(curl -s "${AUTH[@]}" -X POST "$URL/\$/backup/m1"); tid=$(echo "$resp" | grep -o '"taskId" *: *"[0-9]*"' | grep -o '[0-9]*' | tail -1)
task_wait "$tid"
f=$("${DC[@]}" exec -T fuseki sh -c 'ls -1 /fuseki-base/backups/ | grep "^m1" | tail -1')
lines=$("${DC[@]}" exec -T fuseki sh -c "zcat /fuseki-base/backups/$f | grep -c ." )
inf=$("${DC[@]}" exec -T fuseki sh -c "zcat /fuseki-base/backups/$f | grep -c '<http://example.org/a> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://example.org/B>'")
echo "M3 \$/backup: task $tid, file $f, $lines quads (stored 3), inferred 'a a B' lines: $inf"

# M4 delete (filter on), then Fuseki's own behaviour (filter off)
c=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" -X DELETE "$URL/\$/datasets/m1")
left=$("${DC[@]}" exec -T fuseki sh -c 'test -e /fuseki-base/databases/m1 && echo present || echo gone')
mkds m1 >/dev/null; again=$(nq "$URL/m1/stored")
echo "M4 delete (module filter on): DELETE $c; files $left; re-created m1 stored quads $again"
down; LF_JAVA_TOOL_OPTIONS='-Xmx1g -Dlf.dropFilter=false' up fuseki
mkds m4 >/dev/null; upd "$URL/m4/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A }' >/dev/null
c=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" -X DELETE "$URL/\$/datasets/m4")
left=$("${DC[@]}" exec -T fuseki sh -c 'test -e /fuseki-base/databases/m4 && echo present || echo gone')
mkds m4 >/dev/null; again=$(nq "$URL/m4/stored")
echo "M4 delete (filter off, Fuseki alone): DELETE $c; files $left; re-created m4 stored quads $again"
down; up fuseki

# M5 text index over the type
upd "$URL/textprobe/update" 'INSERT DATA { :A rdfs:subClassOf :B . :t a :A ; rdfs:label "lucene probe thing" }' >/dev/null
t1=$(ask "$URL/textprobe/sparql" 'PREFIX text: <http://jena.apache.org/text#> ASK { ?s text:query "probe" }')
t2=$(ask "$URL/textprobe/sparql" 'PREFIX text: <http://jena.apache.org/text#> ASK { ?s text:query "probe" . ?s a :B }')
t3=$(ask "$URL/textprobe/stored" 'ASK { :t a :B }')
echo "M5 text:TextDataset over lf:DatasetRDFSLive: text:query finds $t1; text hit a superclass $t2; /stored inferred $t3"

# M6 compact
mkds m6 >/dev/null; upd "$URL/m6/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A . GRAPH :g { :b a :A } }' >/dev/null
upd "$URL/m6/update" 'DELETE DATA { GRAPH :g { :b a :A } }' >/dev/null
st=$(curl -s -o "$WORK/compact.json" -w '%{http_code}' "${AUTH[@]}" -X POST "$URL/\$/compact/m6?deleteOld=true")
tid=$(grep -o '"taskId" *: *"[0-9]*"' "$WORK/compact.json" | grep -o '[0-9]*' | tail -1); task_wait "$tid"
echo "M6 compact: status $st task $tid; stored quads after $(nq "$URL/m6/stored") (expect 2); ASK a⊑B $(ask "$URL/m6/sparql" 'ASK { :a a :B }'); write after compact $(upd "$URL/m6/update" 'INSERT DATA { :c a :A }') then ASK $(ask "$URL/m6/sparql" 'ASK { :c a :B }')"

# M7 restart
mkds m7 >/dev/null; upd "$URL/m7/update" 'INSERT DATA { :A rdfs:subClassOf :B . GRAPH :g { :b a :A } }' >/dev/null
before=$(nq "$URL/m7/stored")
"${DC[@]}" restart fuseki >/dev/null 2>&1; up fuseki
after=$(nq "$URL/m7/stored"); a=$(ask "$URL/m7/sparql" 'ASK { GRAPH :g { :b a :B } }')
built=$(logs | grep 'm7/: hierarchy' | tail -2 | sed 's/.*\[linked-fuseki\]/[linked-fuseki]/' | tr '\n' ' ')
echo "M7 restart: stored quads $before -> $after; named-graph inference $a; log: $built"

# M8 SERVICE
c=$(curl -s -o /dev/null -w '%{http_code}' --data-urlencode 'query=SELECT * { SERVICE <http://example.org/sparql> { ?s ?p ?o } }' "$URL/m7/sparql")
l=$(logs | grep -A2 'SERVICE <http://example.org/sparql>' | tail -1 | sed 's/.*:: //')
echo "M8 SERVICE: status $c; log: $l"

# M9 lazy rebuild under an 800-commit burst
mkds m9 >/dev/null
upd "$URL/m9/update" 'INSERT DATA { :i a :C800 }' >/dev/null
ask "$URL/m9/sparql" 'ASK { ?s ?p ?o }' >/dev/null
b0=$(logs | grep -c 'm9/: hierarchy rebuilt')
t0=$(ms); for i in $(seq 1 800); do upd "$URL/m9/update" "INSERT DATA { :C$i rdfs:subClassOf :Root }" >/dev/null; done; t1=$(ms)
for i in $(seq 1 800); do upd "$URL/plain/update" "INSERT DATA { :C$i rdfs:subClassOf :Root }" >/dev/null; done; t2=$(ms)
mid=$(logs | grep -c 'm9/: hierarchy rebuilt')
q0=$(ms); a=$(ask "$URL/m9/sparql" 'ASK { :i a :Root }'); q1=$(ms)
rb=$(logs | grep 'm9/: hierarchy rebuilt' | tail -1 | sed 's/.*hierarchy rebuilt/rebuilt/')
echo "M9 lazy rebuild: 800 commits $((t1-t0)) ms on the type vs $((t2-t1)) ms plain TDB2; rebuilds during the burst $((mid-b0)); first ASK $a in $((q1-q0)) ms; rebuilds after it $(( $(logs | grep -c 'm9/: hierarchy rebuilt') - b0 )); $rb"

# M11 copy round trip through /stored
mkds m11src >/dev/null; mkds m11dst >/dev/null
upd "$URL/m11src/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A ; rdfs:label "x"@en . GRAPH :g { :b a :A ; :n 1.50 } }' >/dev/null
curl -s -H 'Accept: application/n-quads' "$URL/m11src/stored" > "$WORK/copy.nq"
c=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/n-quads' --data-binary @"$WORK/copy.nq" "$URL/m11dst")
echo "M11 copy round trip: POST $c; stored quads src $(nq "$URL/m11src/stored") dst $(nq "$URL/m11dst/stored"); inferred dst a⊑B $(ask "$URL/m11dst/sparql" 'ASK { :a a :B }') g:b⊑B $(ask "$URL/m11dst/sparql" 'ASK { GRAPH :g { :b a :B } }')"

# M12 ds and SHACL, with and without ENABLE_*
upd "$URL/ds/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A ; rdfs:label "ds probe" }' >/dev/null
shape='@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix : <http://example.org/> .
:S a sh:NodeShape ; sh:targetClass :B ; sh:property [ sh:path :required ; sh:minCount 1 ] .'
rep=$(printf '%s' "$shape" | curl -s -H 'Content-Type: text/turtle' -H 'Accept: application/n-triples' --data-binary @- "$URL/ds/shacl?graph=default")
foc=$(echo "$rep" | grep -c 'focusNode> <http://example.org/a>')
echo "M12a ds (ENABLE_*=true): infers $(ask "$URL/ds/sparql" 'ASK { :a a :B }'); /ds/stored $(ask "$URL/ds/stored" 'ASK { :a a :B }'); text $(ask "$URL/ds/sparql" 'PREFIX text: <http://jena.apache.org/text#> ASK { ?s text:query "probe" . ?s a :B }'); shacl report focus :a $foc; db dir $("${DC[@]}" exec -T fuseki sh -c 'ls /fuseki-base/databases | tr "\n" " "')"
down; LF_ENABLE=false up fuseki
u=$(upd "$URL/ds/update" 'INSERT DATA { :z a :A }')
s=$(printf '%s' "$shape" | curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: text/turtle' --data-binary @- "$URL/ds/shacl?graph=default")
w=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/n-triples' --data-binary '<http://e/s> <http://e/p> <http://e/o> .' "$URL/ds/data?default")
r=$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: application/n-triples' "$URL/ds/data?default")
echo "M12b ds (ENABLE_* unset): update $u; shacl $s; data PUT $w; data GET $r; sparql $(ask "$URL/ds/sparql" 'ASK { ?s ?p ?o }' || echo err)"
down

# M13 template guard over a stock template
docker run --rm --entrypoint sh secoresearch/fuseki:6.2.0 -c 'unzip -p /jena-fuseki/fuseki-server.jar org/apache/jena/fuseki/server/templates/config-tdb2' > "$LF_STOCK_TEMPLATE" 2>/dev/null
chmod 666 "$LF_STOCK_TEMPLATE"
stock=$(grep -c DatasetRDFSLive "$LF_STOCK_TEMPLATE")
up guard
URL="http://localhost:$GPORT"
now=$(grep -c DatasetRDFSLive "$LF_STOCK_TEMPLATE")
warn=$(logs guard | grep -c 'differed from the module.s template and was replaced')
mkds m13 >/dev/null; upd "$URL/m13/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A }' >/dev/null
echo "M13 template guard: stock file mentions the type $stock -> after start $now; WARN lines $warn; form-created dataset infers $(ask "$URL/m13/sparql" 'ASK { :a a :B }')"
down
URL="http://localhost:$PORT"
fi

# M10 benchmark (needs the generated data)
if [ -n "${LF_BENCH_DATA:-}" ] && [ -f "$LF_BENCH_DATA/instances.nt" ]; then
  mkdir -p "$LF_BENCH_DIR/data" "$LF_BENCH_DIR/db"; chmod -R 777 "$LF_BENCH_DIR"
  cp "$LF_BENCH_DATA"/hierarchy.nt "$LF_BENCH_DATA"/instances.nt "$LF_BENCH_DATA"/named.nq "$LF_BENCH_DIR/data/"
  t0=$(ms)
  docker run --rm -v "$LF_BENCH_DIR:/bench" --entrypoint java "${LF_IMAGE:-linked-fuseki:dev}" \
    -cp '/jena-fuseki/fuseki-server.jar:/javalibs/*' tdb2.tdbloader --loc /bench/db/main \
    /bench/data/instances.nt /bench/data/named.nq >/dev/null 2>&1
  t1=$(ms)
  up bench
  t2=$(ms)
  echo "M10 bench: load $((t1-t0)) ms; start $((t2-t1)) ms; $(logs bench | grep 'hierarchy built' | sed 's/.*\[linked-fuseki\]/[linked-fuseki]/')"
  BENCH_URL="http://localhost:${LF_BENCH_PORT:-3232}" node ../bench/bench.mjs | sed 's/^/M10 /'
  if [ -n "${LF_BENCH_REVERSED:-}" ]; then
    BENCH_SERVICES=bench-plain,bench-stock,bench BENCH_URL="http://localhost:${LF_BENCH_PORT:-3232}" node ../bench/bench.mjs | grep "^Q[0-9]" | sed 's/^/M10r /'
  fi
  echo "M10 rebuild log: $(logs bench | grep 'hierarchy rebuilt' | sed 's/.*\[linked-fuseki\]/[linked-fuseki]/' | tr '\n' ' ')"
  down
else
  echo "M10 bench: skipped (set LF_BENCH_DATA to the directory gen.py wrote)"
fi
