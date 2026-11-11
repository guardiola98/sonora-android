#!/usr/bin/env python3
"""Publish a verified APK and exact GPL source via the existing SSH Git remote.

Requires a clean source checkout and the persistent local release key. Never
publishes signing credentials. No GitHub API token or admin access is needed.
"""
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('verify_release', ROOT / 'tools/verify-release.py')
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)

def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', *args], cwd=cwd, text=True).strip()

apk = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'app/build/outputs/apk/release/app-release.apk'
metadata = verification.verify(apk)
if git('status', '--porcelain'):
    raise SystemExit('Commit source changes before publishing the matching source snapshot.')
source = git('rev-parse', 'HEAD')
provenance = apk.parent / 'provenance.json'
if not provenance.is_file() or json.loads(provenance.read_text()) != metadata | {'sourceCommit': source}:
    raise SystemExit('Run tools/build-release.py on this clean commit before publishing.')
metadata['sourceCommit'] = source
version = metadata['version']
if not re.fullmatch(r'[a-zA-Z0-9._-]+', version):
    raise SystemExit('Unsafe version label.')
remote = git('remote', 'get-url', 'origin')
if remote != 'git@github.com:guardiola98/sonora-android.git':
    raise SystemExit('Confirm the intended publication remote in this script before publishing a fork.')
branch = 'apk-releases'
with tempfile.TemporaryDirectory(prefix='sonora-publish-') as temporary:
    destination = Path(temporary)
    git('init', '--initial-branch=' + branch, cwd=destination)
    git('remote', 'add', 'origin', remote, cwd=destination)
    git('config', 'core.sshCommand', 'ssh -o BatchMode=yes', cwd=destination)
    if git('ls-remote', '--heads', 'origin', branch, cwd=destination):
        git('fetch', '--depth=1', 'origin', branch, cwd=destination)
        git('checkout', '-B', branch, 'FETCH_HEAD', cwd=destination)
    for field in ('user.name', 'user.email'):
        git('config', field, git('config', field), cwd=destination)
    latest = destination / 'latest.json'
    if latest.is_file() and metadata['versionCode'] <= json.loads(latest.read_text())['versionCode']:
        raise SystemExit('The release versionCode must exceed the published versionCode.')
    notes = ROOT / 'docs/release-notes' / (version + '.md')
    metadata['notes'] = notes.read_text(encoding='utf-8').strip() if notes.is_file() else ''
    metadata['sizeBytes'] = apk.stat().st_size
    release = destination / 'releases' / version
    if release.exists():
        raise SystemExit('This version is already published. Increment the app version; do not replace releases.')
    release.mkdir(parents=True)
    filename = 'sonora-' + version + '.apk'
    shutil.copy2(apk, release / filename)
    git('archive', '--format=zip', '--output=' + str(release / 'sonora-source.zip'), source)
    for name in ('LICENSE', 'NOTICE.md'):
        shutil.copy2(ROOT / name, release / name)
    (release / 'SHA256SUMS').write_text(metadata['sha256'] + '  ' + filename + '\n')
    (release / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
    latest.write_text(json.dumps(metadata | {'apk': 'releases/' + version + '/' + filename}, indent=2) + '\n')
    url = 'https://github.com/guardiola98/sonora-android/raw/refs/heads/' + branch + '/releases/' + version + '/' + filename
    (destination / 'README.md').write_text('# Sonora signed APKs\n\n[Download ' + version + '](' + url + ')\n\n'
        'This branch distributes APKs signed with Sonora’s persistent release key. The private key is never stored here. '
        'Each version includes its exact source snapshot, GPL license, attribution, certificate fingerprint and SHA-256.\n\n'
        'Source: [guardiola98/sonora-android](https://github.com/guardiola98/sonora-android/tree/' + source + ').\n\n'
        'Earlier debug APKs may have a different certificate and cannot be upgraded in place. Do not uninstall a library you need without an available backup. '
        'Future releases signed with this key can update this release without clearing app data.\n')
    git('add', 'README.md', 'latest.json', 'releases/' + version, cwd=destination)
    git('commit', '-m', 'Publish signed Sonora ' + version + ' from ' + source[:7], cwd=destination)
    git('push', 'origin', 'HEAD:refs/heads/' + branch, cwd=destination)
    print(url)
