import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / 'schemas/v1/contracts.json').read_bytes())
VALIDATOR = Draft202012Validator({**SCHEMA, '$ref': '#/$defs/githubCheckPublication'})


def publication(**changes):
    return dict(schemaVersion=1, kind='github-check-publication', outputDirectory='/tmp/attempt',
                sourceStatus='passed', outcome='published', httpStatus=201, checkRunId=42,
                request=dict(hash='a' * 64, sizeBytes=123), report=dict(hash='b' * 64, sizeBytes=456),
                repository='owner/repository', headSha='c' * 40, **changes)


class GitHubCheckPublicationSchemaTest(unittest.TestCase):
    def test_published_source_status_is_independent(self):
        Draft202012Validator.check_schema(SCHEMA)
        for status in ('passed', 'failed', 'incomplete', 'cancelled'):
            VALIDATOR.validate({**publication(), 'sourceStatus': status})

    def test_rejected_and_unknown_outcomes(self):
        for status in (400, 401, 403, 422, 499):
            VALIDATOR.validate({**publication(), 'outcome': 'rejected', 'httpStatus': status, 'checkRunId': None})
        for status in (None, 201, 403, 500):
            VALIDATOR.validate({**publication(), 'outcome': 'unknown', 'httpStatus': status, 'checkRunId': None})
        for changes in (dict(outcome='rejected', httpStatus=None, checkRunId=None),
                        dict(outcome='rejected', httpStatus=500, checkRunId=None),
                        dict(outcome='unknown'), dict(httpStatus=200), dict(checkRunId=None), dict(checkRunId=0),
                        dict(httpStatus=0), dict(httpStatus=201.5), dict(checkRunId=1.5)):
            self.assertFalse(VALIDATOR.is_valid({**publication(), **changes}), changes)

    def test_repository_and_sha_match_canonical_cli_output(self):
        for repository in ('a/b', 'owner-1/repo.name_1', 'o/...', 'a' * 100 + '/' + 'r' * 100):
            VALIDATOR.validate({**publication(), 'repository': repository})
        for repository in ('owner/.', 'owner/..', '/repo', 'owner/', 'owner/a/b', '-owner/repo',
                           'owner/repo\n', 'owner/répo', 'a' * 101 + '/repo', 'owner/' + 'r' * 101):
            self.assertFalse(VALIDATOR.is_valid({**publication(), 'repository': repository}), repository)
        for sha in ('c' * 39, 'c' * 41, 'A' * 40, 'g' * 40, 'c' * 40 + '\n'):
            self.assertFalse(VALIDATOR.is_valid({**publication(), 'headSha': sha}), sha)

    def test_stored_and_stdout_require_all_fields_and_reject_unknowns(self):
        value = publication()
        for key in value:
            self.assertFalse(VALIDATOR.is_valid({k: v for k, v in value.items() if k != key}), key)
        for changes in (dict(schemaVersion=2), dict(kind='test-aggregate'), dict(outputDirectory=''),
                        dict(sourceStatus='success'), dict(outcome='failed'), dict(token='secret')):
            self.assertFalse(VALIDATOR.is_valid({**value, **changes}), changes)


if __name__ == '__main__':
    unittest.main()
