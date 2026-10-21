#!/usr/bin/env bash
# Smoke test of the image: a form-created dataset infers, /stored reads stored triples only,
# a quads POST marks the hierarchy, SERVICE is refused, `ds` infers and validates with SHACL,
# and a delete removes the dataset's files.
# Usage: server/scripts/smoke.sh <image>   (port: SMOKE_PORT, default 3233)
set -euo pipefail
IMAGE="${1:?usage: smoke.sh <image>}"
PORT="${SMOKE_PORT:-3233}"
NAME="lf-smoke-$$"
PW=smoke
URL="http://localhost:$PORT"
PREFIX='PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> PREFIX : <http://example.org/>'
FAILED=0

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILED=1; }
check() { if [ "$2" = "$3" ]; then pass "$1 ($2)"; else fail "$1: expected $3, got $2"; fi; }

ask() { # endpoint query -> true|false
  curl -sf -H 'Accept: application/sparql-results+json' --data-urlencode "query=$PREFIX $2" "$1" \
    | grep -o '"boolean" *: *[a-z]*' | grep -o '[a-z]*$'
}
upd() { curl -sf -o /dev/null -w '%{http_code}' -H 'Content-Type: application/sparql-update' --data-binary "$PREFIX $2" "$1"; }

start=$(date +%s)
docker run -d --name "$NAME" -p "$PORT:3030" \
  -e ADMIN_PASSWORD=$PW -e ENABLE_UPDATE=true -e ENABLE_DATA_WRITE=true -e ENABLE_SHACL=true \
  "$IMAGE" >/dev/null
for _ in $(seq 1 60); do
  curl -sf "$URL/\$/ping" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "$URL/\$/ping" >/dev/null || { fail "server did not start"; docker logs "$NAME" | tail -40; exit 1; }
pass "server up"

code=$(curl -s -o /dev/null -w '%{http_code}' -u admin:$PW --data 'dbName=smoke&dbType=tdb2' "$URL/\$/datasets")
check "POST \$/datasets dbType=tdb2" "$code" 200

upd "$URL/smoke/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A . GRAPH :g { :b a :A } }' >/dev/null
check "template dataset infers (/smoke/sparql)" "$(ask "$URL/smoke/sparql" 'ASK { :a a :B }')" true
check "named graph inferred" "$(ask "$URL/smoke/sparql" 'ASK { GRAPH :g { :b a :B } }')" true
check "/smoke/stored holds no inferred triple" "$(ask "$URL/smoke/stored" 'ASK { :a a :B }')" false
n=$(curl -sf -H 'Accept: application/n-quads' "$URL/smoke/stored" | grep -c . || true)
check "GET /smoke/stored quads = inserted" "$n" 3

code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/n-quads' \
  --data-binary '<http://example.org/B> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/C> .' "$URL/smoke")
check "quads POST to the dataset URL" "$code" 200
check "the POST changed the hierarchy" "$(ask "$URL/smoke/sparql" 'ASK { :a a :C }')" true

code=$(curl -s -o /dev/null -w '%{http_code}' --data-urlencode 'query=SELECT * { SERVICE <http://example.org/sparql> { ?s ?p ?o } }' "$URL/smoke/sparql")
check "SERVICE refused" "$code" 422

upd "$URL/ds/update" 'INSERT DATA { :A rdfs:subClassOf :B . :a a :A ; rdfs:label "smoke thing" }' >/dev/null
check "ds infers" "$(ask "$URL/ds/sparql" 'ASK { :a a :B }')" true
check "ds /stored stored-only" "$(ask "$URL/ds/stored" 'ASK { :a a :B }')" false
check "ds text index" "$(ask "$URL/ds/sparql" 'PREFIX text: <http://jena.apache.org/text#> ASK { ?s text:query "smoke" . ?s a :B }')" true
shape='@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix : <http://example.org/> .
:S a sh:NodeShape ; sh:targetClass :B ; sh:property [ sh:path :required ; sh:minCount 1 ] .'
report=$(printf '%s' "$shape" | curl -sf -H 'Content-Type: text/turtle' -H 'Accept: application/n-triples' --data-binary @- "$URL/ds/shacl?graph=default")
echo "$report" | grep -q '<http://www.w3.org/ns/shacl#focusNode> <http://example.org/a>' \
  && pass "ds shacl: a shape on the superclass reports the subclass instance" \
  || fail "ds shacl: no report for :a ($report)"

code=$(curl -s -o /dev/null -w '%{http_code}' -u admin:$PW -X DELETE "$URL/\$/datasets/smoke")
check "DELETE \$/datasets/smoke" "$code" 200
if docker exec "$NAME" test -e /fuseki-base/databases/smoke; then fail "database directory still there"; else pass "database directory removed"; fi

echo "smoke: $(( $(date +%s) - start )) s"
exit $FAILED
