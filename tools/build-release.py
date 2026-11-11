#!/usr/bin/env python3
"""Build a verified signed release and record its exact clean source commit."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()
if git('status', '--porcelain'):
    raise SystemExit('Commit source changes before recording release provenance.')
source = git('rev-parse', 'HEAD')
subprocess.run([str(ROOT / 'gradlew'), '--no-daemon', 'assembleRelease', *sys.argv[1:]], cwd=ROOT, check=True)
if git('status', '--porcelain') or git('rev-parse', 'HEAD') != source:
    raise SystemExit('Source changed during the build; release provenance was not recorded.')
spec = importlib.util.spec_from_file_location('verify_release', ROOT / 'tools/verify-release.py')
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)
apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
metadata = verification.verify(apk) | {'sourceCommit': source}
(apk.parent / 'provenance.json').write_text(json.dumps(metadata, indent=2) + '\n')
print('Verified release provenance:', json.dumps(metadata))
