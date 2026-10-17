import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile

RESOURCE = Path(__file__).resolve().parents[3] / 'src/main/resources/dev/tronto/buildtools/vcpkg_archive.py'
SPEC = importlib.util.spec_from_file_location('vcpkg_archive', RESOURCE)
adapter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adapter)


@unittest.skipIf(os.name == 'nt', 'POSIX adapters; Windows uses upstream native archive tools')
class ArchiveTest(unittest.TestCase):
    def test_real_zip_roundtrip_permissions_links_empty_dirs_and_spaces(self):
        with tempfile.TemporaryDirectory(prefix='vcpkg archives ') as tmp:
            root = Path(tmp)
            source = root / 'package'
            source.mkdir()
            (source / 'share/empty').mkdir(parents=True)
            (source / 'share/.DS_Store').write_text('excluded')
            (source / 'share/.hidden').write_text('keep hidden children')
            (source / 'tool with spaces').write_text('program')
            (source / 'tool with spaces').chmod(0o755)
            os.utime(source / 'tool with spaces', (0, 0))  # Tar sources often have pre-1980 timestamps.
            (source / 'alias').symlink_to('tool with spaces')
            output = root / 'binary cache.zip'
            subprocess.run([sys.executable, str(RESOURCE), 'zip', 'unused', '-9', '--quiet', '-y', '-r', str(output), '*', '--exclude', '.DS_Store'], cwd=source, check=True)
            with zipfile.ZipFile(output) as archive:
                self.assertIsNone(archive.testzip())
                self.assertNotIn('share/.DS_Store', archive.namelist())
                self.assertTrue(stat.S_ISLNK(archive.getinfo('alias').external_attr >> 16))
            for flags in (['-DD', '-qq'], ['-qqo']):
                dest = root / flags[-1]
                dest.mkdir()
                subprocess.run([sys.executable, str(RESOURCE), 'unzip', 'unused', *flags, str(output)], cwd=dest, check=True)
                self.assertEqual('program', (dest / 'alias').read_text())
                self.assertTrue((dest / 'alias').is_symlink())
                self.assertTrue((dest / 'tool with spaces').stat().st_mode & 0o111)
                self.assertTrue((dest / 'share/empty').is_dir())
                self.assertTrue((dest / 'share/.hidden').exists())
            dest = root / 'explicit destination'
            subprocess.run([sys.executable, str(RESOURCE), 'unzip', 'unused', '-DD', '-qq', str(output), '-d', str(dest)], check=True)
            self.assertTrue((dest / 'share/.hidden').exists())
            dest = root / 'attached destination'
            subprocess.run([sys.executable, str(RESOURCE), 'unzip', 'unused', '-DD', '-qq', str(output), '-d' + str(dest)], check=True)
            self.assertTrue((dest / 'share/.hidden').exists())

    def test_reject_unknown_options_and_escaping_paths(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            adapter.unzip(['--unexpected', 'archive'])
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            adapter.compress(['--unexpected'])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / 'escape.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('../escape', 'no')
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                adapter.unzip([str(archive), '-d', str(root / 'out')])
            with zipfile.ZipFile(archive, 'w') as z:
                info = zipfile.ZipInfo('escape')
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                z.writestr(info, '../../escape')
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                adapter.unzip([str(archive), '-d', str(root / 'out')])
