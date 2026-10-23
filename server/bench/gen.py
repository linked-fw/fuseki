# Generates the ~1M-triple benchmark data from schema.org's class hierarchy.
# Usage: python3 gen.py <dir with schema.json and schema-added.json> [instances] [named-graph instances] [listed graphs]
# Writes data/hierarchy.nt, data/instances.nt (hierarchy included) and data/named.nq next to this file,
# and for the registry variant data/instances-only.nt (no hierarchy) and data/listed.nq: the hierarchy
# split over [listed graphs] (default 20) named graphs, each with its record in the graph registry.
import json, random, sys, os
random.seed(42)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data')
os.makedirs(OUT, exist_ok=True)
SRC = os.path.join(sys.argv[1], '')
S = 'http://schema.org/'
RDF_TYPE = '<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>'
SUB = '<http://www.w3.org/2000/01/rdf-schema#subClassOf>'
def expand(c, ctx):
    if c.startswith('http'): return c
    p, _, l = c.partition(':')
    return ctx[p] + l
sub = set()
for f in ['schema.json', 'schema-added.json']:
    d = json.load(open(SRC + f)); ctx = d['@context']
    for n in d['@graph']:
        v = n.get('rdfs:subClassOf')
        if v is None: continue
        for x in (v if isinstance(v, list) else [v]):
            sub.add((expand(n['@id'], ctx), expand(x['@id'], ctx)))
classes = {a for a, b in sub} | {b for a, b in sub}
with open(f'{OUT}/hierarchy.nt', 'w') as h:
    for a, b in sorted(sub): h.write(f'<{a}> {SUB} <{b}> .\n')
print('subClassOf triples', len(sub), 'classes', len(classes))
CL = ['Person','Patient','Organization','Corporation','NGO','LocalBusiness','Restaurant','Hotel','Store','EducationalOrganization',
 'Event','MusicEvent','SportsEvent','BusinessEvent','Festival','ExhibitionEvent',
 'Article','NewsArticle','BlogPosting','ScholarlyArticle','Photograph','VideoObject','AudioObject','Book','Movie','MusicRecording',
 'Dataset','SoftwareApplication','WebPage','Recipe','Review','Report','Painting','Sculpture','Map',
 'Place','City','Country','Museum','Park','Airport','LandmarksOrHistoricalBuildings','Residence',
 'Action','BuyAction','LikeAction','ReviewAction','CreateAction','SearchAction','TravelAction','Product','Vehicle']
for c in CL: assert S + c in classes, c
CREATIVE = {'Article','NewsArticle','BlogPosting','ScholarlyArticle','Photograph','VideoObject','AudioObject','Book','Movie','MusicRecording','Dataset','SoftwareApplication','WebPage','Recipe','Review','Report','Painting','Sculpture','Map'}
N = int(sys.argv[2]) if len(sys.argv) > 2 else 85000
NG = int(sys.argv[3]) if len(sys.argv) > 3 else 10000
B = 'http://example.org/d/'
def lit(s): return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'
inst = [(i, random.choice(CL)) for i in range(N + NG)]
persons = [i for i, c in inst[:N] if c in ('Person','Patient')]
places = [i for i, c in inst[:N] if c in ('Place','City','Country','Museum','Park','Airport','LandmarksOrHistoricalBuildings','Residence')]
WORDS = 'alpha beta gamma delta epsilon river stone light north harbor quiet amber violet market summer'.split()
def emit(w, i, c, g):
    q = lambda s, p, o: w.write(f'{s} {p} {o}{g} .\n')
    s = f'<{B}{c.lower()}/{i}>'
    cnt = 0
    def T(p, o):
        nonlocal cnt; q(s, p, o); cnt += 1
    T(RDF_TYPE, f'<{S}{c}>')
    T(f'<{S}name>', lit(f'{c} {i} ' + ' '.join(random.sample(WORDS, 2))))
    props = ['description','identifier','dateCreated','url','keywords','alternateName','disambiguatingDescription']
    for p in random.sample(props, random.randint(2, 6)):
        v = {'dateCreated': f'"20{random.randint(10,25)}-0{random.randint(1,9)}-1{random.randint(0,9)}"^^<http://www.w3.org/2001/XMLSchema#date>',
             'url': f'"https://ex.org/{i}"'}.get(p, lit(' '.join(random.sample(WORDS, 3))))
        T(f'<{S}{p}>', v)
    nl = random.randint(1, 3)
    links = []
    if c in CREATIVE or c in ('Event','MusicEvent','SportsEvent','BusinessEvent','Festival','ExhibitionEvent','Product') : links.append('image')
    if c in CREATIVE: links.append('author')
    if 'Event' in c or c == 'Festival': links.append('location')
    links += ['knows', 'subjectOf', 'image']
    seen = set()
    for l in links:
        if len(seen) >= nl: break
        if l in seen: continue
        seen.add(l)
        if l == 'image':
            x = f'<{s[1:-1]}/image>'
            T(f'<{S}image>', x)
            q(x, RDF_TYPE, f'<{S}ImageObject>'); q(x, f'<{S}contentUrl>', f'"https://img.ex.org/{i}.jpg"'); q(x, f'<{S}name>', lit(f'image of {i}')); cnt += 3
        elif l == 'author':
            j = random.choice(persons); T(f'<{S}author>', f'<{B}{inst[j][1].lower()}/{j}>')
        elif l == 'location':
            j = random.choice(places); T(f'<{S}location>', f'<{B}{inst[j][1].lower()}/{j}>')
        else:
            j = random.randrange(N); T(f'<{S}{l}>', f'<{B}{inst[j][1].lower()}/{j}>')
    return cnt
tot = 0
with open(f'{OUT}/instances.nt', 'w') as w:
    for a, b in sorted(sub): w.write(f'<{a}> {SUB} <{b}> .\n')
    for i, c in inst[:N]: tot += emit(w, i, c, '')
print('default-graph instance triples', tot, 'instances', N)
tot = 0
with open(f'{OUT}/named.nq', 'w') as w:
    for i, c in inst[N:]: tot += emit(w, i, c, ' <http://example.org/graph/ng1>')
print('named-graph triples', tot, 'instances', NG)
K = int(sys.argv[4]) if len(sys.argv) > 4 else 20
REG = '<https://linked.cm/pkg/core/graph/registry>'
with open(f'{OUT}/instances.nt') as r, open(f'{OUT}/instances-only.nt', 'w') as w:
    for n, line in enumerate(r):
        if n >= len(sub): w.write(line)
with open(f'{OUT}/listed.nq', 'w') as w:
    for k in range(1, K + 1):
        g = f'<http://example.org/graph/onto{k}>'
        w.write(f'{g} <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://linked.cm/ont/core/OntologyGraph> {REG} .\n')
        w.write(f'{g} <http://www.w3.org/ns/sparql-service-description#name> {g} {REG} .\n')
        w.write(f'{g} <https://linked.cm/ont/core/feedsHierarchy> "true"^^<http://www.w3.org/2001/XMLSchema#boolean> {REG} .\n')
    for n, (a, b) in enumerate(sorted(sub)):
        w.write(f'<{a}> {SUB} <{b}> <http://example.org/graph/onto{n % K + 1}> .\n')
print('listed graphs', K, 'subClassOf triples spread over them', len(sub))
