import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "termux_manifest_executor.py"
spec = importlib.util.spec_from_file_location("executor", MODULE_PATH)
executor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(executor)


class ManifestExecutorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="aimediasorter-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source_root = self.root / "source"
        self.destination_root = self.root / "destination"
        self.source_root.mkdir()
        self.source = self.source_root / "İstanbul 'Kahvaltı' $100.jpg"
        self.payload = bytes(range(251)) * 100
        self.source.write_bytes(self.payload)
        os.utime(self.source, ns=(1_684_074_130_123_000_000,) * 2)
        self.record = {
            "resolvedPosixSourcePath": str(self.source),
            "destinationDirectory": str(self.destination_root / "Food"),
            "destinationRootDirectory": str(self.destination_root),
            "sourceRootDirectory": str(self.source_root),
            "destinationFileName": self.source.name,
            "originalModificationTimestampMs": self.source.stat().st_mtime_ns // 1_000_000,
            "sourceSha256": hashlib.sha256(self.payload).hexdigest(),
            "requestedOperation": "MOVE",
        }

    def execute(self, **kwargs):
        return executor.execute_operation(self.record, **kwargs)

    def test_copy_preserves_bytes_and_modification_time(self):
        before = self.source.stat().st_mtime_ns
        result = self.execute(mode="COPY")
        self.assertTrue(self.source.exists())
        self.assertEqual(self.payload, Path(result["destination"]).read_bytes())
        self.assertEqual(before, Path(result["destination"]).stat().st_mtime_ns)
        self.assertTrue(result["timestampPreserved"])

    def test_move_verifies_and_removes_original(self):
        result = self.execute()
        self.assertFalse(self.source.exists())
        self.assertEqual(self.payload, Path(result["destination"]).read_bytes())
        self.assertEqual("MOVE", result["actualOperation"])

    def test_existing_filename_is_preserved(self):
        directory = Path(self.record["destinationDirectory"])
        directory.mkdir(parents=True)
        existing = directory / self.source.name
        existing.write_bytes(b"unrelated existing media")
        result = self.execute(mode="COPY")
        self.assertEqual(b"unrelated existing media", existing.read_bytes())
        self.assertNotEqual(str(existing), result["destination"])

    def test_collision_during_publication_does_not_overwrite(self):
        publish = executor.publish_exclusive
        collision_path = Path(self.record["destinationDirectory"]) / self.source.name
        def racing_publish(staging, directory, name, collision):
            collision_path.write_bytes(b"concurrent destination")
            return publish(staging, directory, name, collision)
        with patch.object(executor, "publish_exclusive", racing_publish):
            result = self.execute()
        self.assertEqual(b"concurrent destination", collision_path.read_bytes())
        self.assertEqual(self.payload, Path(result["destination"]).read_bytes())

    def test_source_changed_after_publication_is_not_deleted(self):
        publish = executor.publish_exclusive
        def mutating_publish(*args):
            destination = publish(*args)
            self.source.write_bytes(b"newer source bytes")
            return destination
        with patch.object(executor, "publish_exclusive", mutating_publish):
            with self.assertRaisesRegex(ValueError, "Source changed"):
                self.execute()
        self.assertEqual(b"newer source bytes", self.source.read_bytes())

    def test_same_size_destination_corruption_keeps_source(self):
        publish = executor.publish_exclusive
        def corrupting_publish(*args):
            destination = publish(*args)
            Path(destination).write_bytes(b"x" * len(self.payload))
            return destination
        with patch.object(executor, "publish_exclusive", corrupting_publish):
            with self.assertRaisesRegex(ValueError, "readback"):
                self.execute()
        self.assertEqual(self.payload, self.source.read_bytes())

    def test_timestamp_failure_keeps_original_by_default(self):
        with patch.object(executor.os, "utime", side_effect=PermissionError("restricted")):
            result = self.execute()
        self.assertTrue(self.source.exists())
        self.assertFalse(result["timestampPreserved"])
        self.assertEqual("COPY", result["actualOperation"])

    def test_interrupted_staging_is_cleaned_up(self):
        with patch.object(executor.os, "fsync", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                self.execute()
        self.assertEqual(self.payload, self.source.read_bytes())
        directory = Path(self.record["destinationDirectory"])
        self.assertEqual([], list(directory.glob(".aimediasorter-*")))

    def test_fallback_publication_is_also_exclusive(self):
        directory = Path(self.record["destinationDirectory"])
        directory.mkdir(parents=True)
        existing = directory / self.source.name
        existing.write_bytes(b"existing")
        with patch.object(executor.os, "link", side_effect=PermissionError(1, "unsupported")):
            result = self.execute(mode="COPY")
        self.assertEqual(b"existing", existing.read_bytes())
        self.assertEqual(self.payload, Path(result["destination"]).read_bytes())

    def test_changed_source_checksum_is_rejected(self):
        self.source.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            self.execute()
        self.assertTrue(self.source.exists())
        self.assertFalse(self.destination_root.exists())

    def test_path_traversal_is_rejected(self):
        self.record["destinationFileName"] = "../archive.jpg"
        with self.assertRaises(ValueError):
            self.execute()
        self.assertTrue(self.source.exists())

    def test_destination_inside_source_is_rejected(self):
        self.record["destinationDirectory"] = str(self.source_root / "Sorted")
        self.record["destinationRootDirectory"] = str(self.source_root)
        with self.assertRaises(ValueError):
            self.execute()
        self.assertTrue(self.source.exists())

    def test_dry_run_and_review_flag_do_not_modify_files(self):
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps({"operations": [self.record]}), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, executor.main(["--manifest", str(manifest)]))
        self.assertTrue(self.source.exists())
        self.assertFalse(self.destination_root.exists())
        self.record["needsManualReview"] = True
        manifest.write_text(json.dumps({"operations": [self.record]}), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, executor.main(["--manifest", str(manifest), "--execute", "--yes"]))
        self.assertTrue(self.source.exists())

    def test_bundled_executor_matches_standalone(self):
        asset = MODULE_PATH.parents[1] / "app/src/main/assets/termux/manifest_executor.py"
        self.assertEqual(MODULE_PATH.read_bytes(), asset.read_bytes())


if __name__ == "__main__":
    unittest.main()
