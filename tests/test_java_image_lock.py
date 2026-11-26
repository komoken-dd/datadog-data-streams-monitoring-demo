"""Catch a Java downgrade or split-brain default in either supported build path."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class JavaImageLockTest(unittest.TestCase):
    def test_build_and_runtime_have_exact_same_archive_integrity_inputs(self):
        lock = (ROOT / 'config/container-images.sh').read_text()
        container = (ROOT / 'apps/Containerfile').read_text()
        for kind in ('JDK', 'JRE'):
            for arch in ('AMD64', 'ARM64'):
                name = f'JAVA_{kind}_{arch}_SHA256'
                expected = re.search(rf'^{name}="([a-f0-9]{{64}})"$', lock, re.M).group(1)
                actual = re.search(rf'^ARG {name}=([a-f0-9]{{64}})$', container, re.M).group(1)
                self.assertEqual(actual, expected, name)
        runtime_name = re.search(r'^JAVA_RUNTIME_IMAGE_NAME="([^"]+)"$', lock, re.M).group(1)
        runtime_index = re.search(r'^JAVA_RUNTIME_IMAGE_INDEX_DIGEST="(sha256:[a-f0-9]{64})"$', lock, re.M).group(1)
        self.assertIn(f'ARG RUNTIME_IMAGE={runtime_name}@{runtime_index}', container)
        self.assertIn('JAVA_PATCHED_VERSION="21.0.13.1"', lock)
        # Verify archive contents at build time, not just a descriptive label.
        self.assertEqual(container.count("grep -Fx 'JAVA_VERSION=\"21.0.13.1\"'"), 2)
        self.assertIn('rm -rf /opt/java/openjdk;', container)
        self.assertIn('rm -rf /runtime-rootfs/opt/java/openjdk;', container)
        self.assertIn('FROM scratch', container)
        self.assertIn('COPY --from=runtime-rootfs /runtime-rootfs/ /', container)


if __name__ == '__main__':
    unittest.main()
