import unittest
from test_coverage_consumer_export_schema import DIGEST, result as sonar_result, validator


def result():
    return {**sonar_result(), 'format': 'jacoco-xml', 'conversionLosses': [],
            'files': dict(schemaVersion=1, files=[dict(path='jacoco/library/jvm/test/library.xml', digest=DIGEST, executable=False)])}


class JacocoXmlExportSchemaTest(unittest.TestCase):
    def test_scoped_originals_allow_same_module_from_distinct_checks(self):
        value = result()
        value['files']['files'].append(dict(path='jacoco/app/jvm/test/library.xml', digest=DIGEST, executable=False))
        validator('coverageConsumerExportResult').validate(value)
        for status in ('failed', 'incomplete', 'cancelled'):
            validator('coverageConsumerExportResult').validate({**value, 'sourceStatus': status, 'status': status})
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'sourceStatus': status}))
        response = dict(schemaVersion=1, outputDirectory='/tmp/new-jacoco', result=value, artifacts=value['files'])
        validator('coverageConsumerExport').validate(response)

    def test_format_specific_paths_and_conversion_losses(self):
        entry = result()['files']['files'][0]
        for path in ('coverage.xml', 'report/jacoco/library/jvm/test/library.xml',
                     'jacoco/library/macosArm64/test/library.xml', 'jacoco/library/jvm/main/library.xml',
                     'jacoco/library/jvm/test/library.xml\n', 'jacoco/1library/jvm/test/library.xml',
                     'jacoco/library/jvm/test/../library.xml', 'jacoco/library/jvm/test/1library.xml'):
            value = {**result(), 'files': dict(schemaVersion=1, files=[{**entry, 'path': path}])}
            self.assertFalse(validator('coverageConsumerExportResult').is_valid(value), path)
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**result(), 'conversionLosses': ['Re-encoded XML']}))
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**result(), 'files': sonar_result()['files']}))
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**sonar_result(), 'files': result()['files']}))
        self.assertFalse(validator('coverageConsumerExportResult').is_valid({**result(), 'files': dict(schemaVersion=1, files=[])}))

    def test_negative_results_have_no_files_and_keep_source_status(self):
        for outcome in ('incomplete', 'unsupported'):
            value = {**result(), 'complete': False, 'outcome': outcome, 'status': 'incomplete',
                     'files': dict(schemaVersion=1, files=[])}
            validator('coverageConsumerExportResult').validate(value)
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'files': result()['files']}))
            self.assertFalse(validator('coverageConsumerExportResult').is_valid({**value, 'status': 'passed'}))
            for source in ('failed', 'cancelled', 'incomplete'):
                validator('coverageConsumerExportResult').validate({**value, 'sourceStatus': source, 'status': source})
        native = {**result(), 'target': 'macosArm64', 'complete': False, 'outcome': 'unsupported',
                  'status': 'incomplete', 'files': dict(schemaVersion=1, files=[])}
        validator('coverageConsumerExportResult').validate(native)


if __name__ == '__main__':
    unittest.main()
