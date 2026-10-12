import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import build_all
import test_instances


class BuildValidationTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.root = Path(self.workspace.name)
        self.target = {"module": "plugin-paper", "minecraft": "server API 1.20.1",
                       "loader": "paper", "runtime_java": 17}
        self.checksums = {f"platform-{i}/mclink-helper": build_all.sha256(f"helper-{i}".encode()) for i in range(6)}
        checksums = self.root / "mod-common/src/main/resources" / build_all.NATIVE_ROOT / "checksums.json"
        checksums.parent.mkdir(parents=True)
        checksums.write_text(json.dumps(self.checksums), encoding="utf-8")
        self.root_patch = patch.object(build_all, "ROOT", self.root)
        self.root_patch.start()

    def tearDown(self):
        self.root_patch.stop()
        self.workspace.cleanup()

    def jar(self, version="0.3.1", java=17, corrupt_helper=False):
        path = self.root / "plugin.jar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("plugin.yml", f"name: Tailcat\nversion: '{version}'\n")
            archive.writestr("com/tailscale/mclink/AsyncProcessSession.class",
                             b"\xca\xfe\xba\xbe\x00\x00" + (java + 44).to_bytes(2, "big"))
            archive.writestr(build_all.NATIVE_ROOT + "checksums.json", json.dumps(self.checksums))
            for i, name in enumerate(self.checksums):
                archive.writestr(build_all.NATIVE_ROOT + name, b"wrong" if corrupt_helper else f"helper-{i}".encode())
        return path

    def test_all_sixteen_targets_are_unique(self):
        matrix = build_all.targets()
        self.assertEqual(16, len(matrix))
        self.assertEqual(16, len({target["module"] for target in matrix}))
        self.assertEqual(3, sum(target["minecraft"] == "26.3" for target in matrix))

    def test_forge_26_2_uses_root_wrapper(self):
        target = next(t for t in build_all.targets() if t["module"] == "mod-forge-26.2")
        self.assertEqual(".", target["wrapper"])
        self.assertEqual(25, target["build_java"])
        self.assertEqual(25, target["runtime_java"])

    def test_forge_1_20_1_uses_reobfuscated_runtime_jar(self):
        target = next(t for t in build_all.targets() if t["module"] == "mod-forge-1.20.1")
        self.assertEqual(build_all.ROOT / target["module"] / "build/reobfJar/output.jar",
                         build_all.runtime_jar_path(target, "0.3.1"))

    def test_other_modules_use_named_build_artifact(self):
        target = next(t for t in build_all.targets() if t["module"] == "mod-neoforge-1.20.1")
        self.assertEqual(build_all.ROOT / target["module"] / "build/libs" /
                         build_all.artifact_name(target, "0.3.1"),
                         build_all.runtime_jar_path(target, "0.3.1"))

    def test_root_fabric_reads_shared_version(self):
        module = self.root / "mod-fabric-26.2"
        module.mkdir()
        (module / "build.gradle").write_text("version = project.mod_version", encoding="utf-8")
        (self.root / "gradle.properties").write_text("mod_version=9.8.7", encoding="utf-8")
        self.assertEqual("9.8.7", build_all.mod_version("mod-fabric-26.2"))

    def test_source_fingerprint_ignores_cache_directories(self):
        (self.root / ".gradle").mkdir()
        matrix = self.root / "support_matrix.json"
        matrix.write_text('{"targets":[]}', encoding="utf-8")
        with patch.object(build_all, "MATRIX", matrix):
            before = build_all.source_fingerprint()
            (self.root / ".gradle/cache-file").write_text("cache", encoding="utf-8")
            self.assertEqual(before, build_all.source_fingerprint())

    def test_valid_artifact_records_hash_and_no_gameplay_claim(self):
        jar = self.jar()
        entry = build_all.inspect_jar(jar, self.target, "0.3.1")
        self.assertEqual(build_all.sha256(jar.read_bytes()), entry["jar_sha256"])
        self.assertFalse(entry["gameplay_verified"])

    def test_blank_paper_version_is_rejected(self):
        with self.assertRaises(RuntimeError):
            build_all.inspect_jar(self.jar(version=""), self.target, "0.3.1")

    def test_wrong_class_java_is_rejected(self):
        with self.assertRaises(RuntimeError):
            build_all.inspect_jar(self.jar(java=25), self.target, "0.3.1")

    def test_native_mismatch_is_rejected(self):
        with self.assertRaises(RuntimeError):
            build_all.inspect_jar(self.jar(corrupt_helper=True), self.target, "0.3.1")

    def fabric_jar(self, java_requirement=">=25"):
        jar = self.jar(java=25)
        with zipfile.ZipFile(jar, "a") as archive:
            archive.writestr("fabric.mod.json", json.dumps({"id": "tailcat-for-minecraft", "version": "0.3.1",
                "depends": {"minecraft": "26.2", "fabricloader": ">=0.19.3", "java": java_requirement}}))
        return jar

    def test_fabric_new_id_is_verified(self):
        target = {**self.target, "loader": "fabric", "mod_id": "tailcat-for-minecraft", "runtime_java": 25}
        self.assertEqual("tailcat-for-minecraft", build_all.inspect_jar(self.fabric_jar(), target, "0.3.1")["mod_id"])

    def test_fabric_java_requirement_must_match_classes(self):
        target = {**self.target, "loader": "fabric", "mod_id": "tailcat-for-minecraft", "runtime_java": 25}
        with self.assertRaises(RuntimeError):
            build_all.inspect_jar(self.fabric_jar(java_requirement=">=21"), target, "0.3.1")


class IsolatedRunnerTest(unittest.TestCase):
    def test_import_never_launches_or_kills_processes(self):
        spec = importlib.util.spec_from_file_location("isolated_test_import", test_instances.__file__)
        module = importlib.util.module_from_spec(spec)
        with patch("subprocess.Popen") as launch, patch("subprocess.run") as command:
            spec.loader.exec_module(module)
        launch.assert_not_called()
        command.assert_not_called()

    def test_old_resource_logs_do_not_imply_title_screen(self):
        status, reason = test_instances.classify_log("Sound engine started")
        self.assertEqual("resource-initialized", status)
        self.assertIsNone(reason)
        self.assertNotEqual("title-screen", status)

    def test_crash_takes_priority_over_resource_loading(self):
        self.assertEqual("startup-error", test_instances.classify_log("Sound engine started\nMixin apply failed")[0])

    def test_process_ownership_requires_exact_new_game_directory(self):
        process = Mock()
        process.is_running.return_value = True
        process.create_time.return_value = 100
        process.name.return_value = "javaw.exe"
        process.cwd.return_value = str(Path.cwd() / "other-minecraft")
        directory = Path.cwd() / "minecraft"
        self.assertFalse(test_instances.process_matches(process, directory, 99))
        process.cwd.return_value = str(directory)
        self.assertFalse(test_instances.process_matches(process, directory, 101))
        self.assertTrue(test_instances.process_matches(process, directory, 99))
        process.is_running.return_value = False
        self.assertFalse(test_instances.process_matches(process, directory, 99))

    def test_clone_preserves_original_logs_and_excludes_worlds(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            instances = root / "instances"
            source = instances / "original"
            game = source / "minecraft"
            (game / "mods").mkdir(parents=True)
            (game / "saves/my-world").mkdir(parents=True)
            (game / "logs").mkdir()
            (game / "logs/latest.log").write_text("old log", encoding="utf-8")
            (game / "mods/tailcat-for-minecraft-old.jar").write_bytes(b"old mod")
            (game / "mods/other-mod.jar").write_bytes(b"other mod")
            (source / "instance.cfg").write_text("name=Original", encoding="utf-8")
            (source / "mmc-pack.json").write_text(json.dumps({"components": [
                {"uid": "net.minecraft", "version": "26.3"},
                {"uid": "net.fabricmc.fabric-loader", "version": "0.19.5"}]}), encoding="utf-8")
            dist = root / "dist"
            dist.mkdir()
            jar = "tailcat-for-minecraft-fabric-26.3-0.3.1.jar"
            (dist / jar).write_bytes(b"new mod")
            (dist / "release-manifest.json").write_text(json.dumps({"artifacts": [
                {"module": "mod-fabric-26.3", "jar_sha256": "test-hash"}]}), encoding="utf-8")
            with patch.object(test_instances, "ROOT", root), patch.object(build_all, "mod_version", return_value="0.3.1"), \
                    patch.object(build_all, "inspect_jar", return_value={"jar_sha256": "test-hash"}):
                clone, clone_game = test_instances.prepare_instance(source, instances, "mod-fabric-26.3")
            self.assertTrue((clone / ".tailcat-test-instance.json").is_file())
            self.assertEqual("old log", (game / "logs/latest.log").read_text(encoding="utf-8"))
            self.assertTrue((game / "saves/my-world").is_dir())
            self.assertFalse((clone_game / "saves").exists())
            self.assertFalse((clone_game / "logs").exists())
            self.assertTrue((clone_game / "mods/other-mod.jar").is_file())
            self.assertTrue((clone_game / "mods" / jar).is_file())
            self.assertFalse((clone_game / "mods/tailcat-for-minecraft-old.jar").exists())


if __name__ == "__main__":
    unittest.main()
