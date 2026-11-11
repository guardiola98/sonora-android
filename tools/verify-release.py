#!/usr/bin/env python3
"""Verify an installable release against the public, pinned Sonora certificate."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def sdk_path():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        local = ROOT / 'local.properties'
        if local.exists():
            sdk = next((line.split('=', 1)[1] for line in local.read_text().splitlines() if line.startswith('sdk.dir=')), None)
    if not sdk:
        raise SystemExit('Set ANDROID_HOME to verify the release APK.')
    return Path(sdk)

def verify(apk):
    build_tools = sdk_path() / 'build-tools' / '35.0.0'
    result = subprocess.run([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], check=True, capture_output=True, text=True)
    signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
    expected = (ROOT / 'signing/certificate.sha256').read_text().strip().lower()
    if [signer.lower() for signer in signers] != [expected]:
        raise SystemExit('The APK does not use the pinned Sonora release certificate.')
    manifest = subprocess.run([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
    if 'application-debuggable' in manifest:
        raise SystemExit('Refusing to publish a debuggable APK as a release.')
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", manifest)
    if not package or package[1] != 'org.sonora.music':
        raise SystemExit('Unexpected application ID.')
    return {'version': package[3], 'versionCode': int(package[2]), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'certificateSha256': expected}

if __name__ == '__main__':
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'app/build/outputs/apk/release/app-release.apk'
    print(json.dumps(verify(apk), indent=2))
