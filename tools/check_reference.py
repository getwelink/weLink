"""Check published routes, callback fields, SDK methods and local document links."""
import ast
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
reference = json.loads((ROOT/'docs/reference.json').read_text(encoding='utf-8'))
endpoints = reference['endpoints']
routes = {(e['method'], '/v1'+e['path']) for e in endpoints}
spec = json.loads((ROOT/'docs/openapi.json').read_text(encoding='utf-8'))
assert routes == {(m.upper(), p) for p, item in spec['paths'].items() for m in item if m in {'get', 'post', 'put', 'delete', 'patch'}}

sources = ['sdk/python/welink/__init__.py', 'sdk/node/welink.js', 'sdk/go/welink/welink.go', 'sdk/java/src/welink/WeLink.java']
python_source = (ROOT/sources[0]).read_text(encoding='utf-8')
ast.parse(python_source, feature_version=8)

def camel(value, upper=False):
    parts = re.split(r'[._]', value)
    return (parts[0].capitalize() if upper else parts[0]) + ''.join(p[0].upper()+p[1:] for p in parts[1:])

for path in sources:
    source = (ROOT/path).read_text(encoding='utf-8')
    documented = set(re.findall(r'\b(GET|POST|PUT|DELETE|PATCH) (/v1/[^\s`]+)', source))
    assert routes <= documented, (path, routes-documented)
    for endpoint in endpoints:
        identifier = endpoint['id']
        if 'python' in path:
            assert 'def '+identifier.replace('.', '_')+'(' in source, identifier
        elif 'java' in path:
            assert camel(identifier)+'(' in source, identifier
        elif 'go' in path:
            assert camel(identifier, True)+'(' in source, identifier
        else:
            group, name = identifier.split('.')
            assert 'self.'+group+' = {' in source, identifier
            assert camel(name)+'(' in source, identifier

webhook = (ROOT/'docs/WEBHOOK.md').read_text(encoding='utf-8')
for obj in reference['events']['message']['objects']:
    assert '## '+obj['name']+'：' in webhook, obj['name']
    for field in obj['fields']:
        assert '`'+field['name']+'`' in webhook, field['name']

for doc in ROOT.rglob('*.md'):
    for destination in re.findall(r'\]\(([^)]+)\)', doc.read_text(encoding='utf-8')):
        if '://' in destination or destination.startswith('#'):
            continue
        target = destination.split('#', 1)[0]
        assert (doc.parent/target).exists(), (doc.relative_to(ROOT), destination)

print('%d routes: four SDKs, Python 3.8 syntax, OpenAPI, callback fields and document links verified' % len(routes))
