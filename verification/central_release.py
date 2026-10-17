"""Complete an OSSRH-compatible upload and verify its Central publication."""
import base64
import json
import os
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

STAGING = 'https://ossrh-staging-api.central.sonatype.com'
PORTAL = 'https://central.sonatype.com'


class CentralRelease:
    def __init__(self, namespace, username, password):
        if not re.fullmatch(r'[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*', namespace):
            raise ValueError('MAVEN_CENTRAL_NAMESPACE must be a Portal namespace')
        if not username or not password:
            raise ValueError('Central Portal token username/password are required')
        self.namespace = namespace
        self.authorization = base64.b64encode(f'{username}:{password}'.encode()).decode()
        self.secrets = (self.authorization, username, password)

    @classmethod
    def from_environment(cls):
        if not os.environ.get('SIGNING_KEY', '').strip():
            raise ValueError('SIGNING_KEY is required for a Central release')
        return cls(os.environ.get('MAVEN_CENTRAL_NAMESPACE') or 'dev.tronto.gdal4k',
                   os.environ.get('MAVEN_CENTRAL_USERNAME'),
                   os.environ.get('MAVEN_CENTRAL_PASSWORD'))

    def redact(self, message):
        for secret in sorted(self.secrets, key=len, reverse=True):
            message = message.replace(secret, '[redacted]')
        return message

    def request(self, method, url):
        request = Request(url, data=b'' if method == 'POST' else None, method=method,
                          headers={'Authorization': 'Bearer ' + self.authorization})
        try:
            with urlopen(request, timeout=60) as response:
                return response.read().decode('utf-8')
        except HTTPError as error:
            error.close()
            raise RuntimeError(f'Central request failed (HTTP {error.code}); inspect the Portal deployment') from None
        except (URLError, TimeoutError):
            raise RuntimeError('Central request did not complete; inspect the Portal before retrying publication') from None

    def repositories(self, state=None):
        query = {'profile_id': self.namespace, 'ip': 'client'}
        if state:
            query['state'] = state
        return json.loads(self.request('GET', STAGING + '/manual/search/repositories?' + urlencode(query)))['repositories']

    def finalize(self, timeout=1800, interval=15):
        # Capture the staging key before transfer so an older release cannot satisfy the status check.
        repositories = self.repositories('open')
        if len(repositories) != 1:
            raise RuntimeError('Expected exactly one open staging repository for this runner and namespace')
        key = repositories[0]['key']
        self.request('POST', STAGING + '/manual/upload/defaultRepository/' +
                     quote(self.namespace, safe='') + '?publishing_type=automatic')
        print('Central accepted automatic publication; waiting for PUBLISHED', flush=True)
        deadline = time.monotonic() + timeout
        deployment_id = None
        previous_state = None
        while time.monotonic() < deadline:
            if not deployment_id:
                matches = [r for r in self.repositories() if r['key'] == key]
                if len(matches) == 1:
                    deployment_id = matches[0].get('portal_deployment_id')
            if deployment_id:
                result = json.loads(self.request('POST', PORTAL + '/api/v1/publisher/status?' +
                                                urlencode({'id': deployment_id})))
                if result.get('deploymentId') != deployment_id:
                    raise RuntimeError('Central returned a different deployment ID')
                state = result.get('deploymentState')
                if state != previous_state:
                    print(f'Central deployment {deployment_id}: {state}', flush=True)
                    previous_state = state
                if state == 'PUBLISHED':
                    return deployment_id
                if state == 'FAILED':
                    raise RuntimeError('Central validation/publication failed: ' +
                                       self.redact(json.dumps(result.get('errors', {}))))
                if state not in {'PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING'}:
                    raise RuntimeError('Unexpected Central deployment state: ' + str(state))
            time.sleep(min(interval, max(0, deadline - time.monotonic())))
        raise RuntimeError('Central publication timed out; inspect the Portal deployment before retrying: ' +
                           str(deployment_id or key))
