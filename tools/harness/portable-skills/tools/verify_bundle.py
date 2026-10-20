#!/usr/bin/env python3
"""Validate portable skill layout, local links, checksums and optional checkout drift."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


def relative_name(path, root):
    """Manifest names use forward slashes on every host."""
    return path.relative_to(root).as_posix()


def validate(root, repo=None):
    errors = []
    skills = sorted((root / 'skills').glob('*/SKILL.md'))
    if len(skills) != 20:
        errors.append(f'Expected 20 skills, found {len(skills)}')
    for path in skills:
        text = path.read_text(encoding='utf-8')
        match = re.match(r'^---\nname: ([a-z0-9-]+)\ndescription: (.+)\n---\n', text)
        if not match or match[1] != path.parent.name or len(match[1]) > 64:
            errors.append(f'Invalid frontmatter/name: {path.relative_to(root)}')
        else:
            try:
                description = json.loads(match[2])
                if not isinstance(description, str) or not description.strip():
                    raise ValueError('Empty description')
            except (ValueError, TypeError):
                errors.append(f'Invalid description: {path.parent.name}')
        metadata = path.parent / 'agents/openai.yaml'
        if not metadata.is_file() or '$' + path.parent.name not in metadata.read_text(encoding='utf-8'):
            errors.append(f'Missing/inconsistent UI metadata: {path.parent.name}')
    for path in root.rglob('*'):
        if path.is_symlink():
            errors.append(f'Symlink in bundle: {path.relative_to(root)}')
        if path.name == '__pycache__' or path.suffix in ('.pyc', '.jks', '.keystore') or path.name in ('.env', 'local.properties'):
            errors.append(f'Unexpected private/generated file: {path.relative_to(root)}')
    for path in root.rglob('*.md'):
        for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', path.read_text(encoding='utf-8')):
            if target.startswith(('http:', 'https:', 'app:', '#', 'mailto:')):
                continue
            target = target.split('#', 1)[0]
            if target and not (path.parent / target).exists():
                errors.append(f'Broken local link in {path.relative_to(root)}: {target}')
    checksums = root / 'SHA256SUMS.txt'
    if checksums.is_file():
        listed = set()
        for line in checksums.read_text(encoding='utf-8').splitlines():
            digest, name = line.split('  ', 1)
            path = root / name
            if path.resolve().is_relative_to(root.resolve()) and path.is_file():
                if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                    errors.append(f'Checksum mismatch: {name}')
            else:
                errors.append(f'Missing/unsafe checksum path: {name}')
            listed.add(name)
        actual = {relative_name(p, root) for p in root.rglob('*') if p.is_file() and p != checksums}
        if actual != listed:
            errors.append('Checksum manifest file set mismatch')
    else:
        errors.append('SHA256SUMS.txt missing')
    drift = []
    if repo:
        source = json.loads((root / 'skills/skerry-architecture/references/source-map.json').read_text(encoding='utf-8'))
        for name, digest in source['contract_sha256'].items():
            path = repo / name
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                drift.append(name)
    return {'skills': len(skills), 'errors': errors, 'checkout_drift': drift,
            'note': 'Structural/hash checks only; not product tests, model availability or new-machine live validation.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path)
    args = parser.parse_args()
    result = validate(Path(__file__).resolve().parents[1], args.repo.expanduser().resolve() if args.repo else None)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return int(bool(result['errors'] or result['checkout_drift']))


if __name__ == '__main__':
    raise SystemExit(main())
