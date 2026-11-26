#!/usr/bin/env python3
"""Validate and atomically publish a QuietFrame JAR with its SHA-256 checksum."""

import argparse
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory

sys.dont_write_bytecode = True
import env

MANIFEST = 'QUIETFRAME-SHA256SUMS.txt'


def check_existing_release(release, jar_name, digest, checksum):
    if release.is_symlink() or not release.is_dir():
        raise SystemExit(f'Existing release path is not a regular directory: {release}.')
    entries = {path.name: path for path in release.iterdir()}
    expected = {jar_name, MANIFEST}
    if entries.keys() != expected:
        missing = sorted(expected - entries.keys())
        extra = sorted(entries.keys() - expected)
        raise SystemExit(f'Existing release entries differ at {release}. Missing {missing}, extra {extra}.')
    for path in entries.values():
        if path.is_symlink() or not path.is_file():
            raise SystemExit(f'Existing release entry is not a regular file: {path}.')
    if env.sha256(entries[jar_name]) != digest or entries[MANIFEST].read_bytes() != checksum:
        raise SystemExit(f'Existing release files differ at {release}.')


def publish_release(staged, release, jar_name, digest, checksum):
    if not release.exists() and not release.is_symlink():
        try:
            staged.rename(release)
            return
        except OSError:
            if not release.exists() and not release.is_symlink():
                raise
    check_existing_release(release, jar_name, digest, checksum)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--to', type=Path, default=env.ROOT / 'dist', help='parent directory for releases')
    parser.add_argument('--jar', type=Path, default=env.BUILD / env.JAR_NAME, help='JAR to validate and publish')
    args = parser.parse_args()
    target = args.to.expanduser().resolve()
    source = args.jar.expanduser().resolve()
    env.jdk()
    if not source.is_file():
        raise SystemExit(f'{source} is missing. Run tools/build.py first.')

    target.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=target, prefix='.quietframe-stage-') as temporary:
        staged = Path(temporary)
        jar = staged / env.JAR_NAME
        shutil.copyfile(source, jar)
        digest = env.sha256(jar)
        with TemporaryDirectory(prefix='quietframe-rebuild-') as rebuild:
            subprocess.run([
                sys.executable, str(env.ROOT / 'tools/build.py'), '--out-dir', rebuild, '--report-inputs',
            ], check=True)
            expected = env.sha256(Path(rebuild) / env.JAR_NAME)
            if digest != expected:
                raise SystemExit(f'Candidate JAR differs from an independent rebuild ({digest} != {expected}).')
        subprocess.run([
            sys.executable, str(env.ROOT / 'tools/test.py'), '--jar', str(jar), '--knot',
        ], check=True)

        checksum = f'{digest}  {jar.name}\n'.encode('ascii')
        (staged / MANIFEST).write_bytes(checksum)
        release = target / f'{jar.stem}-{digest}'
        staged.chmod(0o755)
        publish_release(staged, release, jar.name, digest, checksum)

    print(release / jar.name)
    print(release / MANIFEST)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
