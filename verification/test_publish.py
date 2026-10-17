"""Offline release lifecycle checks; no credentials or remote publication required."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from central_release import CentralRelease
from jvm_binaries import JVM_BINARIES

ROOT = Path(__file__).resolve().parent
ENV = {'MAVEN_CENTRAL_USERNAME': 'fixture-user', 'MAVEN_CENTRAL_PASSWORD': 'fixture-password',
       'SIGNING_KEY': 'fixture-key'}


class CentralLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.release = CentralRelease('dev.tronto.gdal4k', 'fixture-user', 'fixture-password')

    def responses(self, states):
        return [json.dumps({'repositories': [{'key': 'current'}]}), '',
                json.dumps({'repositories': [
                    {'key': 'old', 'portal_deployment_id': 'old-id'},
                    {'key': 'current', 'portal_deployment_id': 'current-id'}]})] + [
                    json.dumps({'deploymentId': 'current-id', 'deploymentState': state}) for state in states]

    def test_waits_for_current_deployment_to_be_published(self):
        with patch.object(self.release, 'request', side_effect=self.responses(['VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED'])) as request, patch('central_release.time.sleep'):
            self.assertEqual(self.release.finalize(), 'current-id')
        self.assertIn('publishing_type=automatic', request.call_args_list[1].args[1])
        self.assertTrue(all('id=current-id' in c.args[1] for c in request.call_args_list[3:]))

    def test_validation_failure_is_not_success(self):
        with patch.object(self.release, 'request', side_effect=self.responses(['FAILED'])):
            with self.assertRaisesRegex(RuntimeError, 'failed'):
                self.release.finalize()

    def test_transfer_failure_is_not_retried(self):
        responses = self.responses([])[:1] + [RuntimeError('transfer failed')]
        with patch.object(self.release, 'request', side_effect=responses) as request:
            with self.assertRaisesRegex(RuntimeError, 'transfer failed'):
                self.release.finalize()
        self.assertEqual(request.call_count, 2)

    def test_ambiguous_staging_does_not_release(self):
        with patch.object(self.release, 'request', return_value=json.dumps({'repositories': [{'key': 'a'}, {'key': 'b'}]})) as request:
            with self.assertRaisesRegex(RuntimeError, 'exactly one'):
                self.release.finalize()
        self.assertEqual(request.call_count, 1)

    def test_timeout_is_not_success(self):
        with patch.object(self.release, 'request', side_effect=self.responses([])[:2]):
            with self.assertRaisesRegex(RuntimeError, 'timed out'):
                self.release.finalize(timeout=0)

    def test_response_must_match_deployment(self):
        responses = self.responses([]) + [json.dumps({'deploymentId': 'old-id', 'deploymentState': 'PUBLISHED'})]
        with patch.object(self.release, 'request', side_effect=responses):
            with self.assertRaisesRegex(RuntimeError, 'different deployment'):
                self.release.finalize()

    def test_http_error_does_not_leak_credentials_or_retry(self):
        with patch('central_release.urlopen', side_effect=HTTPError('https://fixture', 401, 'fixture-password', {}, None)) as request:
            with self.assertRaisesRegex(RuntimeError, 'HTTP 401') as error:
                self.release.request('POST', 'https://fixture')
        self.assertNotIn('fixture-password', str(error.exception))
        self.assertEqual(request.call_count, 1)

    def test_request_authentication_and_redaction(self):
        with patch('central_release.urlopen') as request:
            request.return_value.__enter__.return_value.read.return_value = b'{}'
            self.assertEqual(self.release.request('POST', 'https://fixture'), '{}')
        actual = request.call_args.args[0]
        self.assertEqual(actual.get_header('Authorization'), 'Bearer ' + self.release.authorization)
        self.assertEqual(actual.method, 'POST')
        self.assertNotIn('fixture-password', self.release.redact('error fixture-password'))


class PublisherRoutingTest(unittest.TestCase):
    def run_publisher(self, version='3.12.4-1.0.1', target='mavenCentral', local=False, fail_upload=False, missing_key=False):
        targets = ['jvm', 'android', 'js', 'wasm-js', 'macosarm64', 'linuxx64', 'linuxarm64', 'mingwx64', 'iosarm64', 'iossimulatorarm64']
        coordinates = {m + s for m in ['gdal4k', 'gdal4k-runtime', 'gdal4k-binary'] for s in ['', *['-' + t for t in targets]]} | {'gdal4k-android-runtime'} | {'gdal4k-binary-' + b for b in JVM_BINARIES}
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / 'repo'
            for coordinate in coordinates:
                directory = repo / 'dev/tronto/gdal4k' / coordinate / version
                directory.mkdir(parents=True)
                (directory / f'{coordinate}-{version}.pom').write_text(
                    '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>dev.tronto.gdal4k</groupId>' +
                    f'<artifactId>{coordinate}</artifactId><version>{version}</version></project>')
                (directory / f'{coordinate}-{version}.jar').write_bytes(b'fixture')
            arguments = ['publish.py', '--repo', str(repo)]
            if local:
                arguments += ['--destination', str(Path(temporary) / 'destination')]
            events = self.events = []

            def upload(*args, **kwargs):
                events.append('upload')
                if fail_upload:
                    raise subprocess.CalledProcessError(1, ['fixture-gradle'])

            def finalize():
                events.append('finalize')
                return 'fixture-deployment'

            environment = {**ENV, 'PUBLISH_TARGET': target}
            if missing_key:
                del environment['SIGNING_KEY']
            with patch.dict(os.environ, environment, clear=True), patch('sys.argv', arguments), patch('subprocess.run', side_effect=upload), patch.object(CentralRelease, 'finalize', side_effect=finalize), contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(ROOT / 'publish.py'), run_name='__main__')
            return events

    def test_release_upload_finishes_before_finalization(self):
        self.assertEqual(self.run_publisher(), ['upload', 'finalize'])

    def test_snapshot_github_and_local_never_finalize(self):
        for options in [{'version': '3.12.4-1.0.1-SNAPSHOT'}, {'target': 'githubPackages'}, {'local': True}]:
            with self.subTest(options=options):
                self.assertEqual(self.run_publisher(**options, missing_key=True), ['upload'])

    def test_failed_upload_never_finalizes(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_publisher(fail_upload=True)
        self.assertEqual(self.events, ['upload'])

    def test_missing_release_key_fails_before_upload(self):
        with self.assertRaisesRegex(ValueError, 'SIGNING_KEY'):
            self.run_publisher(missing_key=True)
        self.assertEqual(self.events, [])


if __name__ == '__main__':
    unittest.main()
