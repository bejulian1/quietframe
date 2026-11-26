"""Resolve the JDK and the cached Minecraft and Fabric build dependencies."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.1.0'
JAR_NAME = f'quietframe-{VERSION}.jar'
BUILD = ROOT / 'build'
SOURCE_DIRS = (Path('src/main/java'), Path('src/main/resources'))
LICENSE = Path('LICENSE')
SOURCE_FINGERPRINT = 'META-INF/quietframe-sources.sha256'
JAVA_RELEASE = 25
JAVAC_FLAGS = ('--release', str(JAVA_RELEASE), '-encoding', 'UTF-8', '-proc:none', '-Xlint:all,-serial,-processing,-classfile,-options', '-Werror')
MINECRAFT_VERSION = '26.3'
LOADER_VERSION = '0.19.5'
API_JAR = f'fabric-api-0.164.0+{MINECRAFT_VERSION}.jar'
GAME_JAR = Path(f'libraries/com/mojang/minecraft/{MINECRAFT_VERSION}/minecraft-{MINECRAFT_VERSION}-client.jar')
GAME_JAR_SHA256 = '4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d'
METADATA = (
    f'net.minecraft/{MINECRAFT_VERSION}.json',
    'org.lwjgl3/3.4.3.json',
    f'net.fabricmc.fabric-loader/{LOADER_VERSION}.json',
    f'net.fabricmc.intermediary/{MINECRAFT_VERSION}.json',
)


def executable(home, name):
    suffix = '.exe' if os.name == 'nt' else ''
    return home / 'bin' / f'{name}{suffix}'


def jdk():
    override = os.environ.get('JAVA_HOME')
    if override:
        candidates = [Path(override).expanduser()]
    else:
        roots = (Path.home() / '.local/share/jdks', Path('/usr/lib/jvm'))
        candidates = [path for root in roots for path in sorted(root.glob('*'), reverse=True)]
        if javac := shutil.which('javac'):
            candidates.append(Path(javac).resolve().parent.parent)

    failures = []
    for candidate in dict.fromkeys(path.resolve() for path in candidates):
        for name, pattern in (
            ('javac', r'^javac\s+(?:1\.)?(\d+)(?=[.\s+-]|$)'),
            ('java', r'^(?:openjdk|java) version "(?:1\.)?(\d+)(?=[.\s+\-"]|$)'),
        ):
            program = executable(candidate, name)
            try:
                version = subprocess.run([str(program), '-version'], capture_output=True, text=True, check=True)
            except (OSError, subprocess.CalledProcessError) as error:
                failures.append(f'{program}: {error}')
                break
            output = version.stdout + version.stderr
            match = re.search(pattern, output, re.MULTILINE)
            if not match or int(match[1]) < JAVA_RELEASE:
                failures.append(f'{program} reported {output.strip()!r}')
                break
        else:
            return candidate
    message = f'JDK {JAVA_RELEASE} or newer is required. Set JAVA_HOME to its installation directory.'
    raise SystemExit('\n'.join((message, *failures)))


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def source_fingerprint(root=ROOT):
    paths = [root / LICENSE]
    for directory in SOURCE_DIRS:
        source = root / directory
        if not source.is_dir():
            raise SystemExit(f'Missing source directory: {source}')
        paths.extend(path for path in source.rglob('*') if path.is_file())

    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode('utf-8') + b'\0')
        digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest().encode('ascii') + b'\n'


def verify_client_jar(path):
    actual = sha256(path)
    if actual != GAME_JAR_SHA256:
        raise SystemExit(f'Minecraft {MINECRAFT_VERSION} client SHA-256 mismatch at {path}: '
                         f'expected {GAME_JAR_SHA256}, got {actual}.')


def prism_cache():
    override = os.environ.get('QUIETFRAME_PRISM_CACHE')
    home = Path.home()
    candidates = (Path(override).expanduser(),) if override else (
        Path(os.environ.get('XDG_DATA_HOME') or home / '.local/share') / 'PrismLauncher',
        home / '.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher',
        home / 'Library/Application Support/PrismLauncher',
        Path(os.environ.get('APPDATA') or home) / 'PrismLauncher',
    )
    for candidate in candidates:
        if (candidate / GAME_JAR).is_file():
            verify_client_jar(candidate / GAME_JAR)
            return candidate.resolve()
    raise SystemExit(f'Minecraft {MINECRAFT_VERSION} is missing. Set QUIETFRAME_PRISM_CACHE to its Prism cache.')


def fabric_api(cache):
    override = os.environ.get('QUIETFRAME_MODS')
    folders = (Path(override).expanduser(),) if override else (
        ROOT / 'libs',
        *sorted(cache.glob('instances/*/minecraft/mods')),
        *sorted(cache.glob('instances/*/.minecraft/mods')),
    )
    for folder in folders:
        candidate = folder / API_JAR
        if candidate.is_file():
            return candidate
    raise SystemExit(f'{API_JAR} is missing. Place it in libs/ or set QUIETFRAME_MODS to its mods directory.')


def classpath_libraries(cache):
    libraries = {}
    for name in METADATA:
        metadata = cache / 'meta' / name
        if not metadata.is_file():
            raise SystemExit(f'Prism metadata is missing: {metadata}')
        data = json.loads(metadata.read_text(encoding='utf-8'))
        for entry in data.get('libraries', ()):
            coordinate = entry['name'].split(':')
            if len(coordinate) != 3:
                continue
            group, artifact, version = coordinate
            path = cache / 'libraries' / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.jar'
            if path.is_file():
                libraries[path] = None
            elif not entry.get('rules'):
                raise SystemExit(f'Required Prism library is missing: {path}')
    return list(libraries)


def nested_jars(archive_path, directory):
    directory.mkdir()
    jars = []
    with ZipFile(archive_path) as archive:
        metadata = json.loads(archive.read('fabric.mod.json'))
        for index, entry in enumerate(metadata.get('jars', ())):
            name = entry['file']
            target = directory / f'{index}-{Path(name).name}'
            target.write_bytes(archive.read(name))
            jars.append(target)
    return jars


def classpath(scratch):
    cache = prism_cache()
    libraries = classpath_libraries(cache)
    loader = next((path for path in libraries if path.name == f'fabric-loader-{LOADER_VERSION}.jar'), None)
    if loader is None:
        raise SystemExit(f'Fabric Loader {LOADER_VERSION} is missing from {cache}.')
    api = fabric_api(cache)
    dependencies = [
        cache / GAME_JAR, *libraries, api,
        *nested_jars(api, scratch / 'fabric-api'),
        *nested_jars(loader, scratch / 'fabric-loader'),
    ]
    return os.pathsep.join(map(str, dependencies))


def compile_production(jdk_home, source_root, classes, dependencies):
    classes.mkdir()
    sources = sorted((source_root / SOURCE_DIRS[0]).rglob('*.java'))
    subprocess.run([
        str(executable(jdk_home, 'javac')), *JAVAC_FLAGS,
        '-classpath', dependencies, '-d', str(classes), *map(str, sources),
    ], check=True)
