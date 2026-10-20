#!/usr/bin/env python3
"""Install this portable collection without replacing existing skills."""
import argparse
import os
from pathlib import Path
import shutil
import tempfile


def install(source, destination, dry_run=False):
    skills = sorted(p for p in source.iterdir() if p.is_dir() and (p / 'SKILL.md').is_file())
    if not skills:
        raise ValueError('No skills found')
    conflicts = [p.name for p in skills if os.path.lexists(destination / p.name)]
    if conflicts:
        raise ValueError('Existing skills are preserved. Choose another destination: ' + ', '.join(conflicts))
    for skill in skills:
        if any(p.is_symlink() for p in skill.rglob('*')):
            raise ValueError('Refusing symbolic links in bundle: ' + skill.name)
    if dry_run:
        return [p.name for p in skills]
    destination.mkdir(parents=True, exist_ok=True)
    # Stage first; preserve existing directories and roll back our new ones on failure.
    with tempfile.TemporaryDirectory(prefix='.skerry-install-', dir=destination) as temp:
        staged = Path(temp)
        for skill in skills:
            shutil.copytree(skill, staged / skill.name)
        installed = []
        try:
            for skill in skills:
                target = destination / skill.name
                # mkdir provides an exclusive reservation, including concurrent installs.
                target.mkdir()
                installed.append(target)
                for child in (staged / skill.name).iterdir():
                    shutil.move(str(child), target / child.name)
        except BaseException:
            for target in installed:
                shutil.rmtree(target)
            raise
    return [p.name for p in skills]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dest', type=Path, default=Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex'))) / 'skills')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1] / 'skills'
    try:
        names = install(source, args.dest.expanduser().resolve(), args.dry_run)
    except (OSError, ValueError) as exc:
        parser.exit(1, f'{exc}\n')
    print(('Would install' if args.dry_run else 'Installed') + f' {len(names)} skills in {args.dest}')
    for name in names:
        print(name)


if __name__ == '__main__':
    main()
