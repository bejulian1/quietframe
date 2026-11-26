#!/usr/bin/env python3
"""Verify the QuietFrame archive and run unit tests and Fabric Knot client smoke tests."""

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import shutil
from struct import unpack
import subprocess
import sys
from tempfile import TemporaryDirectory
from unittest.mock import patch
from zipfile import BadZipFile, ZipFile

sys.dont_write_bytecode = True
import deliver
import env

SOURCES, RESOURCES = (env.ROOT / path for path in env.SOURCE_DIRS)
TESTS = env.ROOT / 'src/test/java'
MOD_FILE = 'fabric.mod.json'
MIXIN_FILE = 'quietframe.mixins.json'
ICON = 'assets/quietframe/icon.png'
REPOSITORY = 'https://github.com/bejulian1/quietframe'
PUBLIC_DOCS = (
    'README.md', 'CHANGELOG.md', 'CONTRIBUTING.md', 'CODE_OF_CONDUCT.md', 'SECURITY.md',
    'docs/ARCHITECTURE.md', 'bench/README.md',
)
COMPATIBILITY_DOCS = {'README.md', 'bench/README.md'}
PACKAGE = 'no.julian.quietframe'
MIXINS = {
    'FramerateLimitTrackerAccessor', 'MinecraftMixin', 'ScreenshotMixin', 'SoundEngineMixin', 'WindowMixin',
}
LANG = Path('assets/quietframe/lang')
LANGUAGES = ('en_us', 'nb_no', 'no_no')
TRANSLATION_KEY = re.compile(r'"((?:key\.quietframe\.[a-z_]+|quietframe\.(?:badge|bypass|command|state|status)(?:\.[a-z_]+)*))"')
FORMAT_TOKEN = re.compile(r'%%|%(?:[1-9][0-9]*\$)?s|%')
# Split flagged spellings so the scanner can check its own source.
COMPANION = re.compile(rb'tick' rb'sight|lux' rb'stride|swift' rb'draw|light' rb'shade', re.IGNORECASE)
PRIVATE = re.compile(
    rb'all' rb'verk|/ho' rb'me/|/run/me' rb'dia/|/Us' rb'ers/|\\Us' rb'ers\\'
    rb'|\b(?:cod' rb'ex|clau' rb'de|gemi' rb'ni|(?:chat)?g' rb'pt|open' rb'ai|anthro' rb'pic'
    rb'|son' rb'net|op' rb'us|hai' rb'ku)\b',
    re.IGNORECASE,
)


def read_json_object(jar, name):
    def unique_object(pairs):
        obj = dict(pairs)
        if len(obj) != len(pairs):
            raise ValueError(f'{name} contains duplicate JSON keys')
        return obj

    def invalid_constant(constant):
        raise ValueError(f'{name} contains an invalid JSON constant: {constant}')

    obj = json.loads(jar.read(name), object_pairs_hook=unique_object, parse_constant=invalid_constant)
    if not isinstance(obj, dict):
        raise ValueError(f'{name} must contain a JSON object')
    return obj


def check_metadata(jar):
    mod = read_json_object(jar, MOD_FILE)
    expected = {
        'schemaVersion': 1, 'id': 'quietframe', 'version': env.VERSION, 'name': 'QuietFrame',
        'license': 'MIT', 'authors': ['Julian'], 'environment': 'client',
        'entrypoints': {'client': [f'{PACKAGE}.QuietFrameClient']},
        'mixins': [MIXIN_FILE], 'icon': ICON,
        'contact': {'homepage': REPOSITORY, 'sources': REPOSITORY, 'issues': f'{REPOSITORY}/issues'},
        'depends': {
            'minecraft': f'={env.MINECRAFT_VERSION}', 'fabricloader': f'>={env.LOADER_VERSION}',
            'fabric-api': '>=0.164.0', 'java': f'>={env.JAVA_RELEASE}',
        },
    }
    for field, value in expected.items():
        if mod.get(field) != value or type(mod[field]) is not type(value):
            raise ValueError(f'Unexpected {MOD_FILE} field {field}')

    mixins = read_json_object(jar, MIXIN_FILE)
    expected = {
        'required': True, 'package': f'{PACKAGE}.mixin',
        'plugin': f'{PACKAGE}.config.QuietFrameMixinPlugin', 'compatibilityLevel': 'JAVA_25',
        'injectors': {'defaultRequire': 1},
    }
    for field, value in expected.items():
        if mixins.get(field) != value or type(mixins[field]) is not type(value):
            raise ValueError(f'Unexpected {MIXIN_FILE} field {field}')
    client = mixins.get('client')
    if (not isinstance(client, list) or any(not isinstance(name, str) for name in client)
            or len(client) != len(MIXINS) or set(client) != MIXINS or {'mixins', 'server'} & mixins.keys()):
        raise ValueError('Unexpected mixin list or environment')


def check_translations(jar):
    locales = {locale: read_json_object(jar, (LANG / f'{locale}.json').as_posix()) for locale in LANGUAGES}
    english = locales['en_us']
    used = {'key.category.quietframe.controls'}
    for source in SOURCES.rglob('*.java'):
        used.update(TRANSLATION_KEY.findall(source.read_text(encoding='utf-8')))
    if used != english.keys():
        raise ValueError(f'English translation key mismatch: {sorted(used ^ english.keys())}')

    for locale, translations in locales.items():
        if translations.keys() != english.keys():
            raise ValueError(f'{locale} translation key mismatch: {sorted(translations.keys() ^ english.keys())}')
        for key, value in translations.items():
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f'{locale} contains an invalid translation for {key}')
            tokens = Counter(FORMAT_TOKEN.findall(value))
            if '%' in tokens or tokens != Counter(FORMAT_TOKEN.findall(english[key])):
                raise ValueError(f'{locale} placeholder mismatch for {key}')


def check_icon(jar):
    if ICON not in jar.namelist():
        raise ValueError(f'Missing release icon: {ICON}')
    png = jar.read(ICON)
    ihdr_prefix = b'\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR'
    iend_chunk = b'\x00\x00\x00\x00IEND\xaeB`\x82'
    if len(png) < 45 or not png.startswith(ihdr_prefix) or not png.endswith(iend_chunk):
        raise ValueError(f'{ICON} must be a PNG')
    width, height, depth, color_type = unpack('>IIBB', png[16:26])
    if (width, height, depth, color_type) != (512, 512, 8, 6):
        raise ValueError(f'{ICON} must be a 512 × 512 RGBA PNG')


def check_clean(name, contents, *, allow_companions=False):
    encoded_name = name.encode('utf-8')
    if (PRIVATE.search(encoded_name) or PRIVATE.search(contents) or COMPANION.search(encoded_name)
            or (not allow_companions and COMPANION.search(contents))):
        raise ValueError(f'Stale or private name in {name}')


def check_archive(path, classes):
    expected = {env.LICENSE.as_posix(): (env.ROOT / env.LICENSE).read_bytes(),
                env.SOURCE_FINGERPRINT: env.source_fingerprint()}
    for root in (classes, RESOURCES):
        for file in root.rglob('*'):
            if file.is_file():
                name = file.relative_to(root).as_posix()
                if name in expected:
                    raise ValueError(f'Duplicate artifact input {name}')
                expected[name] = file.read_bytes()

    with ZipFile(path) as jar:
        entries = jar.namelist()
        if len(entries) != len(set(entries)) or set(entries) != expected.keys():
            raise ValueError(f'Archive entry mismatch: {sorted(set(entries) ^ expected.keys())}')
        for name, contents in expected.items():
            if jar.read(name) != contents:
                raise ValueError(f'Archive entry differs from independent compile or source: {name}')
            check_clean(name, contents)
        check_metadata(jar)
        check_icon(jar)
        check_translations(jar)
        if not expected['LICENSE'].startswith(b'MIT License\n\nCopyright (c) 2026 Julian\n'):
            raise ValueError('Incorrect license or copyright')

    paths = [env.ROOT / name for name in (
        env.LICENSE, '.gitignore', *PUBLIC_DOCS, 'tools/env.py', 'tools/build.py', 'tools/test.py',
        'tools/deliver.py', 'tools/icons.py', 'tools/render_docs_images.py',
    )]
    for directory in ('src', '.github'):
        paths.extend(path for path in (env.ROOT / directory).rglob('*') if path.is_file())
    for file in sorted(paths):
        name = file.relative_to(env.ROOT).as_posix()
        # These docs describe the supported stack. Private names remain blocked in every file.
        check_clean(name, file.read_bytes(), allow_companions=name in COMPATIBILITY_DOCS)


def check_release_tools(scratch):
    def expect_rejection(action, message):
        try:
            action()
        except SystemExit as error:
            if message not in str(error):
                raise ValueError(f'Unexpected rejection: {error}') from error
        else:
            raise ValueError(f'Release tool should have rejected input with {message!r}')

    stage = scratch / 'test-stage'
    stage.mkdir()
    release = scratch / 'test-release'
    jar = stage / env.JAR_NAME
    jar.write_bytes(b'test JAR')
    digest = env.sha256(jar)
    checksum = f'{digest}  {jar.name}\n'.encode('ascii')
    (stage / deliver.MANIFEST).write_bytes(checksum)

    def publish():
        deliver.publish_release(stage, release, jar.name, digest, checksum)

    release.mkdir()
    expect_rejection(publish, 'Missing')
    release.rmdir()
    publish()
    publish()
    extra = release / 'unexpected'
    extra.touch()
    expect_rejection(publish, "extra ['unexpected']")
    if not extra.exists():
        raise ValueError('Delivery changed the existing release directory')
    extra.unlink()

    published_jar = release / jar.name
    alternate_jar = scratch / 'identical.jar'
    published_jar.rename(alternate_jar)
    published_jar.symlink_to(alternate_jar)
    expect_rejection(publish, 'not a regular file')
    published_jar.unlink()
    alternate_jar.rename(published_jar)

    manifest = release / deliver.MANIFEST
    manifest.write_bytes(b'incorrect manifest')
    expect_rejection(publish, 'files differ')
    manifest.write_bytes(checksum)
    real_directory = release.with_name('test-release-real')
    release.rename(real_directory)
    release.symlink_to(real_directory, target_is_directory=True)
    expect_rejection(publish, 'not a regular directory')

    cache = env.prism_cache()
    fake_cache = scratch / 'modified-cache'
    modified_jar = fake_cache / env.GAME_JAR
    modified_jar.parent.mkdir(parents=True)
    shutil.copyfile(cache / env.GAME_JAR, modified_jar)
    with ZipFile(modified_jar, 'a') as archive:
        archive.comment = b'changed client archive comment'
    with patch.dict(os.environ, {'QUIETFRAME_PRISM_CACHE': str(fake_cache)}):
        expect_rejection(env.prism_cache, 'client SHA-256 mismatch')
    print('Release directory and pinned client input regression checks passed.', flush=True)


def run_tests(artifact, scratch, jdk, dependencies, run_knot):
    java = str(env.executable(jdk, 'java'))
    compiler = [str(env.executable(jdk, 'javac')), *env.JAVAC_FLAGS]
    tests = scratch / 'tests'
    tests.mkdir()
    compile_path = os.pathsep.join((str(artifact), dependencies))
    subprocess.run([*compiler, '-cp', compile_path, '-d', str(tests),
                    *map(str, sorted(TESTS.rglob('*.java')))], check=True)
    subprocess.run([java, '-ea', '-cp', os.pathsep.join((str(tests), compile_path)),
                    f'{PACKAGE}.CoreTests', str(scratch / 'unit')], cwd=scratch, check=True)
    if not run_knot:
        return

    launcher = scratch / 'RunKnotTests.java'
    launcher.write_text('''
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

public class RunKnotTests {
    public static void main(String[] args) throws Exception {
        Knot knot = new Knot(EnvType.CLIENT);
        ClassLoader loader = knot.init(new String[0]);
        knot.addToClassPath(Path.of(args[0]));
        Class.forName(args[1], true, loader).getMethod("main", String[].class)
            .invoke(null, (Object) new String[] {args[2]});
    }
}
''', encoding='utf-8')
    launcher_classes = scratch / 'launcher'
    launcher_classes.mkdir()
    subprocess.run([*compiler, '-cp', dependencies, '-d', str(launcher_classes), str(launcher)], check=True)
    cache = env.prism_cache()
    game_jar = cache / env.GAME_JAR
    runtime = os.pathsep.join(map(str, (launcher_classes, game_jar, *env.classpath_libraries(cache))))
    mods = (artifact, env.fabric_api(cache))
    for conflict in ('', 'dynamic_fps', 'fpsreducer'):
        game_dir = scratch / f'knot-{conflict or "active"}'
        game_dir.mkdir()
        staged = list(mods)
        if conflict:
            fixture = scratch / f'{conflict}.jar'
            with ZipFile(fixture, 'w') as jar:
                jar.writestr(MOD_FILE, json.dumps({'schemaVersion': 1, 'id': conflict, 'version': '1.0.0'}))
            staged.append(fixture)
        result = subprocess.run([
            java, '-ea', f'-Dfabric.gameJarPath={game_jar}', f'-Dfabric.addMods={os.pathsep.join(map(str, staged))}',
            '-Dmixin.debug.export=true', '-Dmixin.debug.export.decompile=false', '-Dmixin.debug.countInjections=true',
            '-XX:ActiveProcessorCount=4', '-Xmx1g', '-cp', runtime, launcher.stem,
            str(tests), f'{PACKAGE}.KnotSmoke', conflict,
        ], cwd=game_dir, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
        passed = [line for line in result.stdout.splitlines() if line.startswith('KnotSmoke passed')]
        if result.returncode or len(passed) != 1:
            print(result.stdout, end='', flush=True)
            result.check_returncode()
            raise ValueError('Missing Fabric Knot smoke result')
        print(passed[0], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=env.BUILD / env.JAR_NAME, help='JAR to validate')
    parser.add_argument('--knot', action=argparse.BooleanOptionalAction, default=True,
                        help='verify client Mixin application with Fabric Knot (default: enabled)')
    args = parser.parse_args()
    path = args.jar.expanduser().resolve()
    if not path.is_file():
        raise SystemExit(f'{path} is missing. Run tools/build.py first.')
    jdk = env.jdk()
    with TemporaryDirectory() as directory:
        scratch = Path(directory)
        snapshot = scratch / env.JAR_NAME
        shutil.copyfile(path, snapshot)
        dependencies = env.classpath(scratch)
        classes = scratch / 'production'
        env.compile_production(jdk, env.ROOT, classes, dependencies)
        check_archive(snapshot, classes)
        print('Archive contents, rebuilt classes, metadata, icon, license, locales and public naming passed.', flush=True)
        check_release_tools(scratch)
        run_tests(snapshot, scratch, jdk, dependencies, args.knot)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error
    except (BadZipFile, OSError, ValueError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from error
