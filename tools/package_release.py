"""Create the documented integration kit from source; no credentials required."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', 'node_modules', '__pycache__', '.venv', 'out', 'dist', '.welink-echo'}
SUFFIXES = {'.py', '.mjs', '.js', '.json', '.toml', '.go', '.md', '.txt', '.java', '.yml', '.yaml', '.png', '.svg'}


def build(version, commit, output):
    if not re.fullmatch(r'v\d{4}\.\d{2}\.\d{2}', version):
        raise ValueError('Use a date tag such as v2026.11.04')
    selected = [ROOT / name for name in ('README.md', 'RELEASE.md', 'CHANGELOG.md', 'LICENSE', '.gitignore')]
    for folder in ('sdk', 'examples', 'docs', 'tests', 'tools', 'assets', '.github'):
        for path in (ROOT / folder).rglob('*'):
            if not path.is_file() or EXCLUDED.intersection(path.relative_to(ROOT).parts):
                continue
            if path.suffix not in SUFFIXES and path.name not in ('go.mod', 'go.sum'):
                continue
            if path.is_symlink():
                raise ValueError('Symlinks are not included')
            selected.append(path)
    selected.sort(key=lambda path: path.relative_to(ROOT).as_posix())
    files = {path.relative_to(ROOT).as_posix(): path.read_bytes() for path in selected}
    manifest = {'release': version, 'source_commit': commit, 'files': {
        name: {'size': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in files.items()}}
    encoded = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    output.mkdir(parents=True, exist_ok=True)
    archive = output / ('welink-integration-kit-' + version[1:] + '.zip')
    stamp = tuple(map(int, version[1:].split('.'))) + (0, 0, 0)
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as zipped:
        for name, raw in list(files.items()) + [('MANIFEST.json', encoded)]:
            info = zipfile.ZipInfo('welink-integration-kit/' + name, stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zipped.writestr(info, raw)
    manifest_path = output / 'MANIFEST.json'
    manifest_path.write_bytes(encoded)
    sums = '\n'.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name for path in (archive, manifest_path)) + '\n'
    (output / 'SHA256SUMS.txt').write_bytes(sums.encode('ascii'))
    with zipfile.ZipFile(archive) as zipped:
        assert zipped.testzip() is None
        assert set(zipped.namelist()) == {'welink-integration-kit/' + name for name in files} | {'welink-integration-kit/MANIFEST.json'}
        for name, raw in files.items():
            assert zipped.read('welink-integration-kit/' + name) == raw
    return {'archive': archive.name, 'files': len(files), 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--commit', default='not-specified')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    print(json.dumps(build(args.version, args.commit, args.output)))
