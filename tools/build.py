#!/usr/bin/env python3
"""Build the reproducible QuietFrame release JAR from a source snapshot."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
import zlib

sys.dont_write_bytecode = True
import env


def report_inputs(jdk, dependencies):
    java = subprocess.run([str(env.executable(jdk, 'java')), '-version'],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=True)
    javac = subprocess.check_output([str(env.executable(jdk, 'javac')), '-version'], text=True)
    jars = [Path(entry) for entry in dependencies.split(os.pathsep)]
    print(json.dumps({
        'java': java.stdout.splitlines(),
        'javac': javac.strip(),
        'python': sys.version.split()[0],
        'zlib': zlib.ZLIB_RUNTIME_VERSION,
        'classpath': [{'jar': jar.name, 'sha256': env.sha256(jar)} for jar in jars],
    }), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out-dir', type=Path, default=env.BUILD, help='directory for the built JAR')
    parser.add_argument('--report-inputs', action='store_true', help='print toolchain and classpath identities')
    args = parser.parse_args()
    output = args.out_dir.expanduser().resolve()
    jdk = env.jdk()
    output.mkdir(parents=True, exist_ok=True)
    artifact = output / env.JAR_NAME

    with TemporaryDirectory(dir=output) as temporary:
        scratch = Path(temporary)
        for directory in env.SOURCE_DIRS:
            shutil.copytree(env.ROOT / directory, scratch / directory)
        shutil.copyfile(env.ROOT / env.LICENSE, scratch / env.LICENSE)
        resources = scratch / env.SOURCE_DIRS[1]
        fingerprint = env.source_fingerprint(scratch)
        classes = scratch / 'classes'
        dependencies = env.classpath(scratch)
        if args.report_inputs:
            report_inputs(jdk, dependencies)
        env.compile_production(jdk, scratch, classes, dependencies)

        entries = {env.LICENSE.as_posix(): (scratch / env.LICENSE).read_bytes(),
                   env.SOURCE_FINGERPRINT: fingerprint}
        for directory in (classes, resources):
            for path in directory.rglob('*'):
                if path.is_file():
                    name = path.relative_to(directory).as_posix()
                    if name in entries:
                        raise SystemExit(f'Duplicate JAR entry: {name}')
                    entries[name] = path.read_bytes()

        packaged = scratch / env.JAR_NAME
        with ZipFile(packaged, 'w') as archive:
            for name, contents in sorted(entries.items()):
                entry = ZipInfo(name)
                entry.create_system = 3
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, contents, compress_type=ZIP_DEFLATED)
        digest = env.sha256(packaged)
        packaged.replace(artifact)

    print(f'{artifact}  {digest}')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
