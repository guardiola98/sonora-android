import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import json
import zipfile

spec = importlib.util.spec_from_file_location('ci_release', Path(__file__).resolve().parents[1] / 'ci-release.py')
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class ReleaseGuards(unittest.TestCase):
    def fixture(self, root):
        meta = {'version': '0.7.2-beta01', 'versionCode': 10, 'sha256': 'a' * 64, 'certificateSha256': 'b' * 64, 'sourceCommit': 'c' * 40}
        (root / 'release.json').write_text(json.dumps(meta))
        (root / 'sonora-0.7.2-beta01.apk').write_bytes(b'verified separately')
        (root / 'SHA256SUMS').write_text('a' * 64 + '  sonora-0.7.2-beta01.apk\n')
        for name in ['LICENSE', 'NOTICE.md']:
            (root / name).write_text('notice')
        with zipfile.ZipFile(root / 'sonora-source.zip', 'w') as archive:
            archive.writestr('app/build.gradle.kts', 'source')
            archive.comment = meta['sourceCommit'].encode()
        return meta

    def test_existing_apk_must_match_published_manifest_and_version(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            metadata = self.fixture(root)
            with patch.object(ci, 'verifier') as verify:
                verify.return_value.verify.return_value = {k: v for k, v in metadata.items() if k != 'sourceCommit'}
                self.assertEqual(metadata, ci.verify_bundle(root, '0.7.2-beta01', 10))
                with self.assertRaises(ValueError):
                    ci.verify_bundle(root, '0.7.2-beta01', 11)
                verify.return_value.verify.return_value['sha256'] = 'd' * 64
                with self.assertRaises(ValueError):
                    ci.verify_bundle(root)

    def test_missing_matching_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            metadata = self.fixture(root)
            (root / 'sonora-source.zip').unlink()
            with patch.object(ci, 'verifier') as verify:
                verify.return_value.verify.return_value = {k: v for k, v in metadata.items() if k != 'sourceCommit'}
                with self.assertRaises(ValueError):
                    ci.verify_bundle(root)

    def test_published_asset_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            release = {'id': 1, 'draft': False, 'assets': [{'name': 'LICENSE', 'digest': 'sha256:wrong'}]}
            with patch.object(ci, 'DIST', root), patch.object(ci, 'verify_bundle', return_value={'version': '0.7.2-beta01'}), patch.object(ci, 'api', return_value=release) as api:
                with self.assertRaises(SystemExit):
                    ci.publish_github()
                api.assert_called_once_with('releases/tags/v0.7.2-beta01')


if __name__ == '__main__':
    unittest.main()
