#!/usr/bin/env python3
"""Exercise Helios startup with fake Java/psql: no server or DB is started."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent

class HeliosStartTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='lab1-helios-test-')
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name)
        shutil.copyfile(ROOT / 'scripts/helios-start.sh', self.app / 'helios-start.sh')
        for name in ('is-lab1.war', 'schema.sql', 'payara-micro.jar', 'postgresql.jar'):
            (self.app / name).write_text('test fixture\n')
        self.pgpass = self.app / '.pgpass'
        self.pgpass.write_text('*:*:*:s465142:test-password\n')
        self.pgpass.chmod(0o600)
        self.bin = self.app / 'bin'
        self.bin.mkdir()
        self.write_program('java', '''import os,sys
if '-XshowSettings:properties' in sys.argv:
    print('java.specification.version = '+os.environ.get('MOCK_JAVA_MAJOR','17'),file=sys.stderr)
else:
    assert os.environ['DB_PASSWORD']==os.environ['EXPECTED_PASSWORD']
    assert os.environ['DB_URL']=='jdbc:postgresql://pg:5432/studs?currentSchema='+os.environ['DB_SCHEMA']
    assert os.environ['DB_PASSWORD'] not in ' '.join(sys.argv)
    assert '--port' in sys.argv and '46514' in sys.argv
    assert '--nohazelcast' in sys.argv
    assert '--nocluster' not in [arg.lower() for arg in sys.argv]
    assert '--addlibs' in sys.argv
    assert os.environ['AUTH_USERS_FILE'].endswith('/accounts/users.properties')
    print('JAVA_EXECUTED')
''')
        self.write_program('psql', '''import os,sys
from pathlib import Path
assert 'PGPASSWORD' not in os.environ
if os.environ.get('MOCK_PSQL_FAIL'):
    sys.exit(3)
if '-f' in sys.argv:
    phase='migrate'
    assert os.environ['PGOPTIONS']=='-c search_path='+os.environ['DB_SCHEMA']
    result='PSQL_EXECUTED'
else:
    sql=sys.stdin.read()
    if 'SELECT COALESCE' in sql:
        phase='probe'
        result=os.environ.get('MOCK_SCHEMA_STATUS','ready')
    elif 'SELECT has_database_privilege' in sql:
        phase='database_privilege'
        result=os.environ.get('MOCK_DATABASE_CREATE','f')
    elif 'CREATE SCHEMA IF NOT EXISTS' in sql:
        phase='create'
        assert os.environ.get('MOCK_DATABASE_CREATE')=='t', 'CREATE DATABASE permission must not be required for an existing schema'
        result='CREATE SCHEMA'
    else:
        assert 'AS schema, pg_get_userbyid' in sql
        phase='diagnose'
        result='schema | owner | usage_ok | create_ok'
with Path(os.environ['MOCK_PSQL_TRACE']).open('a') as trace:
    print(phase,file=trace)
print(result)
''')
        self.env = {**os.environ, 'PATH': str(self.bin)+os.pathsep+os.environ['PATH'],
                    'JAVA_BIN': str(self.bin/'java'),
                    'PGPASSFILE': str(self.pgpass), 'AUTH_USERS_FILE': str(self.app/'accounts/users.properties'),
                    'DB_HOST': 'pg', 'DB_PORT': '5432', 'DB_NAME': 'studs', 'DB_USER': 's465142',
                    'DB_SCHEMA': 's465142', 'HTTP_PORT': '46514', 'EXPECTED_PASSWORD': 'test-password',
                    'MOCK_PSQL_TRACE': str(self.app/'psql.trace')}

    def write_program(self, name, body):
        path = self.bin/name
        path.write_text('#!'+sys.executable+'\n'+body)
        path.chmod(0o700)

    def run_start(self):
        result = subprocess.run(['sh', str(self.app/'helios-start.sh')], env=self.env,
                                text=True, capture_output=True, timeout=10)
        self.assertNotIn(self.env['EXPECTED_PASSWORD'], result.stdout+result.stderr)
        return result

    def test_wildcard_entry_and_loopback_configuration(self):
        r = self.run_start()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('JAVA_EXECUTED', r.stdout)
        self.assertEqual((self.app/'accounts').stat().st_mode & 0o777, 0o700)
        self.assertIn('http-listener.address=127.0.0.1', (self.app/'helios-preboot.txt').read_text())

    def test_escaped_colon_and_backslash(self):
        self.pgpass.write_text(r'*:*:*:s465142:pa\:ss\\word'+'\n')
        self.env['EXPECTED_PASSWORD'] = 'pa:ss\\word'
        self.assertEqual(self.run_start().returncode, 0)

    def test_first_matching_entry_wins(self):
        self.pgpass.write_text('pg:5432:studs:s465142:first-password\n*:*:*:s465142:other-password\n')
        self.env['EXPECTED_PASSWORD'] = 'first-password'
        self.assertEqual(self.run_start().returncode, 0)

    def test_wrong_user_does_not_start_or_migrate(self):
        self.pgpass.write_text('*:*:*:another_user:test-password\n')
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn('PSQL_EXECUTED', r.stdout)
        self.assertNotIn('JAVA_EXECUTED', r.stdout)

    def test_insecure_password_file_is_rejected(self):
        self.pgpass.chmod(0o644)
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('0600', r.stderr)
        self.assertNotIn('JAVA_EXECUTED', r.stdout)

    def test_unsafe_schema_is_rejected(self):
        self.env['DB_SCHEMA'] = 'public;DROP SCHEMA public'
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn('PSQL_EXECUTED', r.stdout)

    def test_migration_failure_does_not_start_java(self):
        self.env['MOCK_PSQL_FAIL'] = '1'
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn('JAVA_EXECUTED', r.stdout)

    def test_unsupported_java_is_rejected(self):
        self.env['MOCK_JAVA_MAJOR'] = '8'
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn('JAVA_EXECUTED', r.stdout)

    def test_no_python_command_is_required_on_remote_path(self):
        for tool in ('sh', 'awk', 'stat', 'id', 'dirname', 'mkdir', 'chmod'):
            (self.bin/tool).symlink_to(shutil.which(tool))
        self.env['PATH'] = str(self.bin)
        self.env['PYTHON_BIN'] = '/missing/python'
        r = self.run_start()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('JAVA_EXECUTED', r.stdout)

    def test_freebsd_stat_format_is_supported(self):
        self.write_program('stat', '''import os,stat,sys
assert sys.argv[1:3]==['-Lf','%u %Lp']
s=os.stat(sys.argv[3])
print(str(s.st_uid)+' '+format(stat.S_IMODE(s.st_mode),'o'))
''')
        self.assertEqual(self.run_start().returncode, 0)

    def test_wrong_password_file_owner_is_rejected(self):
        self.write_program('stat', '''import os
print(str(os.getuid()+1)+' 600')
''')
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn('PSQL_EXECUTED', r.stdout)

    def test_crlf_comments_and_malformed_entries(self):
        self.pgpass.write_bytes(b'# comment\r\n\r\nbad:record\r\n*:*:*:s465142:extra:field\r\n*:*:*:s465142:test-password\r\n')
        self.assertEqual(self.run_start().returncode, 0)

    def test_inherited_pgpassword_does_not_override_pgpass(self):
        self.env['PGPASSWORD'] = 'unrelated-old-password'
        r = self.run_start()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn('unrelated-old-password', r.stdout+r.stderr)

    def test_existing_schema_without_database_create_permission(self):
        self.env['MOCK_DATABASE_CREATE'] = 'f'
        r = self.run_start()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.app/'psql.trace').read_text().splitlines(), ['probe', 'migrate'])
        self.assertIn('JAVA_EXECUTED', r.stdout)

    def test_missing_schema_is_created_only_with_database_permission(self):
        self.env.update(MOCK_SCHEMA_STATUS='missing', MOCK_DATABASE_CREATE='t')
        r = self.run_start()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.app/'psql.trace').read_text().splitlines(),
                         ['probe', 'database_privilege', 'create', 'migrate'])

    def test_missing_schema_without_permission_stops_with_diagnostics(self):
        self.env.update(MOCK_SCHEMA_STATUS='missing', MOCK_DATABASE_CREATE='f')
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('Set DB_SCHEMA', r.stderr)
        self.assertIn('usage_ok', r.stderr)
        self.assertNotIn('JAVA_EXECUTED', r.stdout)
        self.assertEqual((self.app/'psql.trace').read_text().splitlines(),
                         ['probe', 'database_privilege', 'diagnose'])

    def test_existing_schema_without_usage_or_create_is_rejected(self):
        self.env['MOCK_SCHEMA_STATUS'] = 'denied'
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('USAGE and CREATE', r.stderr)
        self.assertEqual((self.app/'psql.trace').read_text().splitlines(), ['probe', 'diagnose'])
        self.assertNotIn('JAVA_EXECUTED', r.stdout)

    def test_schema_override_is_shared_by_migration_and_jdbc(self):
        self.env['DB_SCHEMA'] = 's465142_lab1'
        r = self.run_start()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.app/'psql.trace').read_text().splitlines(), ['probe', 'migrate'])

    def test_unknown_schema_probe_result_fails_closed(self):
        self.env['MOCK_SCHEMA_STATUS'] = 'unexpected'
        r = self.run_start()
        self.assertNotEqual(r.returncode, 0)
        self.assertNotIn('JAVA_EXECUTED', r.stdout)
        self.assertEqual((self.app/'psql.trace').read_text().splitlines(), ['probe'])

if __name__ == '__main__':
    unittest.main()
