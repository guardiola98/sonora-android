#!/usr/bin/env python3
"""Prepare immutable Sonora distributions and publish matching GitHub Releases."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / 'dist-release'
REPO = 'guardiola98/sonora-android'
FILES = ['sonora-source.zip', 'LICENSE', 'NOTICE.md', 'SHA256SUMS', 'release.json']


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', *args], cwd=cwd, text=True).strip()


def version():
    gradle = (ROOT / 'app/build.gradle.kts').read_text()
    name = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)"', gradle)[1]
    code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle)[1])
    return name, code


def verifier():
    spec = importlib.util.spec_from_file_location('verify_release', ROOT / 'tools/verify-release.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify_bundle(directory, expected_version=None, expected_code=None):
    metadata = json.loads((directory / 'release.json').read_text())
    name = metadata['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', name):
        raise ValueError('Invalid version')
    if expected_version and name != expected_version:
        raise ValueError('Published version mismatch')
    if expected_code and metadata['versionCode'] != expected_code:
        raise ValueError('Published versionCode mismatch; increase both version fields')
    apk = directory / f'sonora-{name}.apk'
    actual = verifier().verify(apk)
    if any(metadata.get(key) != value for key, value in actual.items()):
        raise ValueError('APK does not match release metadata')
    if not re.fullmatch(r'[0-9a-f]{40}', metadata.get('sourceCommit', '')):
        raise ValueError('Invalid source commit')
    for filename in FILES:
        if not (directory / filename).is_file():
            raise ValueError(f'Missing {filename}')
    sums = (directory / 'SHA256SUMS').read_text().splitlines()
    if f"{actual['sha256']}  {apk.name}" not in sums:
        raise ValueError('APK checksum is missing or incorrect')
    with zipfile.ZipFile(directory / 'sonora-source.zip') as source:
        if source.testzip() or 'app/build.gradle.kts' not in source.namelist() or source.comment.decode() != metadata['sourceCommit']:
            raise ValueError('Invalid source archive')
    return metadata


def prepare():
    name, code = version()
    if DIST.exists():
        shutil.rmtree(DIST)
    DIST.mkdir()
    git('fetch', '--no-tags', '--depth=1', 'origin', 'apk-releases')
    distribution = git('rev-parse', 'FETCH_HEAD')
    relative = f'releases/{name}'
    existing = subprocess.run(['git', 'cat-file', '-e', f'{distribution}:{relative}/release.json'], cwd=ROOT, capture_output=True).returncode == 0
    if existing:
        # Reuse the exact already-published APK and GPL source; never rebuild a version.
        for filename in [f'sonora-{name}.apk', *FILES]:
            with (DIST / filename).open('wb') as output:
                subprocess.run(['git', 'show', f'{distribution}:{relative}/{filename}'], cwd=ROOT, stdout=output, check=True)
        metadata = verify_bundle(DIST, name, code)
        print(f'Reusing immutable official distribution {name}')
    else:
        latest = json.loads(git('show', f'{distribution}:latest.json'))
        if code <= latest['versionCode']:
            raise SystemExit('New releases must increase versionCode and versionName.')
        required = ['SONORA_KEYSTORE_BASE64', 'SONORA_STORE_PASSWORD', 'SONORA_KEY_ALIAS', 'SONORA_KEY_PASSWORD']
        if not all(os.environ.get(key) for key in required):
            raise SystemExit('Official signing secrets are missing. Run tools/configure-ci-signing.py with an authenticated GitHub CLI. No unsigned release was published.')
        import base64
        with tempfile.TemporaryDirectory(prefix='sonora-sign-') as temporary:
            keystore = Path(temporary) / 'release.p12'
            keystore.write_bytes(base64.b64decode(os.environ['SONORA_KEYSTORE_BASE64'], validate=True))
            keystore.chmod(0o600)
            environment = dict(os.environ, SONORA_KEYSTORE_PATH=str(keystore))
            environment.pop('SONORA_KEYSTORE_BASE64', None)
            subprocess.run([sys.executable, str(ROOT / 'tools/build-release.py')], cwd=ROOT, env=environment, check=True)
        apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
        metadata = json.loads((apk.parent / 'provenance.json').read_text())
        if metadata['version'] != name or metadata['versionCode'] != code:
            raise SystemExit('Build version does not match source')
        notes = ROOT / 'docs/release-notes' / f'{name}.md'
        if not notes.is_file():
            raise SystemExit(f'Missing release notes: {notes.relative_to(ROOT)}')
        metadata |= {'sizeBytes': apk.stat().st_size, 'notes': notes.read_text().strip()}
        shutil.copy2(apk, DIST / f'sonora-{name}.apk')
        git('archive', '--format=zip', f'--output={DIST / "sonora-source.zip"}', metadata['sourceCommit'])
        for filename in ['LICENSE', 'NOTICE.md']:
            shutil.copy2(ROOT / filename, DIST / filename)
        (DIST / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
        (DIST / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}\n' for file in sorted(DIST.iterdir())))
        verify_bundle(DIST, name, code)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'version={name}\nsource={metadata["sourceCommit"]}\nexisting={str(existing).lower()}\n')


def publish_branch():
    metadata = verify_bundle(DIST)
    name = metadata['version']
    # actions/checkout v6+ scopes credentials to this checkout and its worktrees.
    # A separate clone would lose that includeIf authentication on future releases.
    git('fetch', '--no-tags', '--depth=1', 'origin', 'apk-releases')
    with tempfile.TemporaryDirectory(prefix='sonora-channel-') as temp:
        destination = Path(temp) / 'channel'
        git('worktree', 'add', '--detach', str(destination), 'FETCH_HEAD')
        try:
            release = destination / 'releases' / name
            if release.exists():
                for file in DIST.iterdir():
                    if not (release / file.name).is_file() or (release / file.name).read_bytes() != file.read_bytes():
                        raise SystemExit('Refusing to replace an existing release')
                print('Distribution branch already contains these exact files')
                return
            latest = json.loads((destination / 'latest.json').read_text())
            if metadata['versionCode'] <= latest['versionCode']:
                raise SystemExit('Refusing to downgrade the update channel')
            shutil.copytree(DIST, release)
            latest = metadata | {'apk': f'releases/{name}/sonora-{name}.apk'}
            (destination / 'latest.json').write_text(json.dumps(latest, indent=2) + '\n')
            (destination / 'README.md').write_text(f'# Sonora signed APKs\n\n[Download {name}](https://github.com/{REPO}/raw/refs/heads/apk-releases/{latest["apk"]})\n\nOfficial APKs use the persistent Sonora signing key. Each release includes matching GPL source, notices and checksums. Install over the previous official version to preserve your library.\n')
            git('add', '.', cwd=destination)
            git('-c', 'user.name=github-actions[bot]', '-c', 'user.email=41898282+github-actions[bot]@users.noreply.github.com', 'commit', '-m', f'Publish signed Sonora {name}', cwd=destination)
            git('push', 'origin', 'HEAD:refs/heads/apk-releases', cwd=destination)
        finally:
            git('worktree', 'remove', '--force', str(destination))


def api(endpoint, method='GET', data=None, binary=False):
    url = endpoint if endpoint.startswith('https://uploads.github.com/') else f'https://api.github.com/repos/{REPO}/{endpoint}'
    headers = {'Authorization': f'Bearer {os.environ["GH_TOKEN"]}', 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
    if data is not None:
        headers['Content-Type'] = 'application/octet-stream' if binary else 'application/json'
        data = data if binary else json.dumps(data).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def publish_github():
    metadata = verify_bundle(DIST)
    name, tag = metadata['version'], 'v' + metadata['version']
    try:
        release = api(f'releases/tags/{tag}')
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        release = api('releases', 'POST', {'tag_name': tag, 'target_commitish': metadata['sourceCommit'], 'name': f'Sonora {name}', 'body': metadata.get('notes', ''), 'draft': True, 'prerelease': bool(re.search(r'beta|alpha|rc', name))})
    existing = {asset['name']: asset for asset in release['assets']}
    for file in sorted(DIST.iterdir()):
        digest = 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()
        if file.name in existing:
            if existing[file.name].get('digest') != digest:
                raise SystemExit(f'Refusing to overwrite release asset {file.name}; checksum differs or cannot be verified')
            continue
        if not release['draft']:
            raise SystemExit(f'Published release is missing {file.name}; refusing to mutate it')
        upload = release['upload_url'].split('{')[0] + '?name=' + urllib.parse.quote(file.name)
        api(upload, 'POST', file.read_bytes(), binary=True)
    if release['draft']:
        api(f'releases/{release["id"]}', 'PATCH', {'draft': False, 'make_latest': 'false' if release['prerelease'] else 'legacy'})
    print(f'https://github.com/{REPO}/releases/tag/{tag}')


if __name__ == '__main__':
    {'prepare': prepare, 'channel': publish_branch, 'github': publish_github}[sys.argv[1]]()
