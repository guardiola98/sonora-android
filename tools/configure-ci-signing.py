#!/usr/bin/env python3
"""Upload the EXISTING Sonora signing key to encrypted Actions secrets via gh."""
import base64
import os
import re
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
if not shutil.which('gh'):
    raise SystemExit('Install GitHub CLI (gh), then run gh auth login before this script.')
subprocess.run(['gh', 'auth', 'status'], check=True)
values = {}
for line in (ROOT / '.signing/signing.properties').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip()
keystore = Path(values['storeFile'])
if not keystore.is_absolute():
    keystore = ROOT / keystore
certificate = subprocess.check_output(['keytool', '-list', '-v', '-keystore', str(keystore), '-storepass:env', 'SONORA_STORE_PASSWORD', '-alias', values['keyAlias']], env=dict(os.environ, SONORA_STORE_PASSWORD=values['storePassword']), text=True)
match = re.search(r'SHA256:\s*([0-9A-Fa-f:]+)', certificate)
expected = (ROOT / 'signing/certificate.sha256').read_text().strip().lower()
if not match or match[1].replace(':', '').lower() != expected:
    raise SystemExit('Refusing to upload a key that does not match the pinned release certificate.')
secrets = {
    'SONORA_KEYSTORE_BASE64': base64.b64encode(keystore.read_bytes()).decode(),
    'SONORA_STORE_PASSWORD': values['storePassword'],
    'SONORA_KEY_ALIAS': values['keyAlias'],
    'SONORA_KEY_PASSWORD': values['keyPassword'],
}
for name, value in secrets.items():
    subprocess.run(['gh', 'secret', 'set', name, '--repo', 'guardiola98/sonora-android'], input=value, text=True, check=True)
print('The existing signing key is configured in encrypted GitHub Actions secrets. No secret was printed or written to the repository.')
