import contextlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
G = runpy.run_path(str(BIN / "droiddeck-gog-maintenance"))["main"].__globals__


class GogMaintenance(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.control = G["control"](self.folder)
        (self.folder / ".droiddeck-store.json").write_text(json.dumps({"store": "gog", "id": "1"}))

    def test_only_downloaded_windows_executables_can_be_patches(self):
        download = self.control / "downloads"
        download.mkdir()
        patch = download / "patch.exe"
        patch.write_bytes(b"MZtest")
        self.assertEqual(patch, G["validate_installer"](self.folder, patch))
        outside = self.folder / "outside.exe"
        outside.write_bytes(b"MZtest")
        with self.assertRaises(ValueError):
            G["validate_installer"](self.folder, outside)
        patch.write_bytes(b"not PE")
        with self.assertRaises(ValueError):
            G["validate_installer"](self.folder, patch)

    def test_game_launch_lease_excludes_updater_and_releases_on_owner_exit(self):
        import fcntl
        owner = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(lambda: owner.poll() is None and owner.kill())
        lease = subprocess.Popen([sys.executable, str(BIN / "droiddeck-gog-maintenance"), "lease", str(self.folder), str(owner.pid)],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lease.stdout.close)
        self.addCleanup(lease.stderr.close)
        self.addCleanup(lambda: lease.poll() is None and lease.kill())
        self.assertEqual(b"ready\n", lease.stdout.readline())
        with G["lock_file"](self.control) as stream:
            with self.assertRaises(BlockingIOError):
                fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        owner.terminate(); owner.wait()
        lease.wait(timeout=5)
        with G["lock_file"](self.control) as stream:
            fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_incomplete_update_cannot_launch(self):
        (self.folder / ".droiddeck-store.json").write_text(json.dumps({"store": "gog", "id": "1", "state": "updating"}))
        with self.assertRaises(ValueError):
            G["lease"](self.folder, os.getpid())

    def test_running_game_and_wrong_prefix_are_distinguished(self):
        proc = self.folder / "proc"
        for pid, prefix, name in [(10, "/prefix/pfx", "game.exe"), (11, "/other/pfx", "other.exe"), (12, "/prefix/pfx", "services.exe")]:
            row = proc / str(pid); row.mkdir(parents=True)
            (row / "environ").write_bytes(b"WINEPREFIX=" + prefix.encode() + b"\0")
            (row / "cmdline").write_bytes(name.encode() + b"\0")
        self.assertEqual([10], G["active"]("/prefix", proc))

    def test_interrupted_patch_is_not_started_again(self):
        (self.control / "operation.json").write_text(json.dumps({"phase": "patch-applying"}))
        with mock.patch.dict(G, {"validate_installer": lambda *a: self.folder / "patch.exe"}):
            with self.assertRaises(ValueError):
                G["start"](str(self.folder), "2147483666", "patch.exe", "game.exe")

    def test_registry_and_installer_use_same_prefix_without_cloud_launch(self):
        download = self.control / "downloads"; download.mkdir()
        patch = download / "patch.exe"; patch.write_bytes(b"MZtest")
        G["write"](self.control / "operation.json", {"phase": "patch-starting"})
        calls = []
        setup = {"main": lambda: None}
        def setup_main():
            pass
        setup_main.__globals__["apply_gog_registry"] = lambda *a: calls.append(("registry", a[1]))
        fake = mock.Mock(); fake.wait.return_value = 0
        def spawn(argv, **kwargs):
            self.assertEqual("run", argv[1])
            self.assertEqual("1", kwargs["env"]["DROIDDECK_GOG_MAINTENANCE"])
            calls.append(("patch", kwargs["env"]["STEAM_COMPAT_DATA_PATH"]))
            return fake
        with mock.patch.dict(G, {"runner": lambda *a: ("/proton", {"STEAM_COMPAT_DATA_PATH": str(self.folder / "prefix"), "DROIDDECK_GOG_MAINTENANCE": "1"}, str(self.folder / "prefix")),
                                 "active": lambda *a: []}), \
             mock.patch.object(G["runpy"], "run_path", return_value={"main": setup_main}), \
             mock.patch.object(G["subprocess"], "Popen", side_effect=spawn):
            self.assertEqual(0, G["run_patch"](str(self.folder), "2147483666", str(patch), "game.exe"))
        self.assertEqual([("registry", str(self.folder / "prefix")), ("patch", str(self.folder / "prefix"))], calls)
        self.assertEqual("done", G["read"](self.control / "patch-status.json")["state"])
        self.assertEqual("updating", G["read"](self.folder / ".droiddeck-store.json")["state"])


if __name__ == "__main__":
    unittest.main()
