import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
MATRIX = Path(__file__).with_name("support_matrix.json")
NATIVE_ROOT = "assets/mclink/native/"


def targets():
    return json.loads(MATRIX.read_text(encoding="utf-8"))["targets"]


def java_major(home):
    java = home / "bin" / ("java.exe" if os.name == "nt" else "java")
    javac = home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    if not java.is_file() or not javac.is_file():
        return None
    result = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=10)
    match = re.search(r'version "(?:1\.)?(\d+)', result.stderr + result.stdout)
    return int(match[1]) if result.returncode == 0 and match else None


def find_jdks(required, explicit):
    candidates = [Path(value) for value in explicit]
    for major in required:
        for key in (f"TAILCAT_JAVA_{major}_HOME", f"JAVA{major}_HOME"):
            if os.environ.get(key):
                candidates.append(Path(os.environ[key]))
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    java = shutil.which("javac")
    if java:
        candidates.append(Path(java).resolve().parent.parent)
    roots = [Path("C:/OpenJDK"), Path("C:/Program Files/Java"),
             Path("C:/Program Files/Eclipse Adoptium"), Path("/usr/lib/jvm"),
             Path.home() / ".jdks"]
    for root in roots:
        if root.is_dir():
            candidates.extend(path for path in root.iterdir() if path.is_dir())
    jdks = {}
    for home in dict.fromkeys(path.resolve() for path in candidates):
        major = java_major(home)
        if major is not None:
            jdks.setdefault(major, home)
    missing = required - jdks.keys()
    if missing:
        raise RuntimeError(f"Missing JDKs {sorted(missing)}. Use --java-home PATH or TAILCAT_JAVA_<major>_HOME.")
    return jdks


def gradle_command(target, jdks, tasks):
    wrapper = ROOT / target["wrapper"] / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise RuntimeError(f"Missing wrapper: {wrapper}")
    home = jdks[target["build_java"]]
    return [str(wrapper), "-p", str(ROOT / target["module"]), *tasks,
            f"-Dorg.gradle.java.home={home}",
            "-Porg.gradle.java.installations.paths=" + ",".join(str(path) for path in jdks.values()),
            "--console=plain", "--max-workers=2"]


def run_gradle(target, jdks, tasks, log):
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdks[target["build_java"]])
    env["PATH"] = str(jdks[target["build_java"]] / "bin") + os.pathsep + env.get("PATH", "")
    command = gradle_command(target, jdks, tasks)
    print(f"Building {target['module']} with JDK {target['build_java']}", flush=True)
    with log.open("w", encoding="utf-8") as output:
        # A .bat wrapper requires cmd.exe on Windows; the argument list is quoted by subprocess.
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text(encoding="utf-8", errors="replace")[-12000:], flush=True)
        raise RuntimeError(f"{target['module']} failed. Full log: {log}")


def mod_version(module):
    gradle = (ROOT / module / "build.gradle").read_text(encoding="utf-8")
    match = re.search(r"(?m)^version\s*=\s*['\"]([^'\"]+)", gradle)
    if match:
        return match[1]
    property_file = ROOT / module / "gradle.properties"
    if not property_file.is_file():
        property_file = ROOT / "gradle.properties"
    props = property_file.read_text(encoding="utf-8")
    match = re.search(r"(?m)^mod_version\s*=\s*(\S+)", props)
    if not match:
        raise RuntimeError(f"Cannot read mod version: {module}")
    return match[1]


def artifact_name(target, version):
    suffix = "paper-plugin" if target["loader"] == "paper" else f"{target['loader']}-{target['minecraft']}"
    return f"tailcat-for-minecraft-{suffix}-{version}.jar"


def runtime_jar_path(target, version):
    module = ROOT / target["module"]
    if target["module"] == "mod-forge-1.20.1":
        # ForgeGradle's build/libs JAR is still in the development namespace.
        # reobfJar/output.jar is the runtime artifact Minecraft can load.
        return module / "build/reobfJar/output.jar"
    return module / "build/libs" / artifact_name(target, version)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def inspect_jar(jar, target, version):
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError(f"Duplicate entries in {jar.name}")
        classes = [name for name in names if name.startswith("com/tailscale/mclink/") and name.endswith(".class")]
        if not classes or "com/tailscale/mclink/AsyncProcessSession.class" not in names:
            raise RuntimeError(f"Missing Tailcat lifecycle classes in {jar.name}")
        majors = set()
        for name in classes:
            header = archive.read(name)[:8]
            if header[:4] != b"\xca\xfe\xba\xbe":
                raise RuntimeError(f"Invalid class: {name}")
            majors.add(int.from_bytes(header[6:8], "big"))
        minimum_java = max(majors) - 44
        if minimum_java != target["runtime_java"]:
            raise RuntimeError(f"{jar.name}: class Java {minimum_java}, expected {target['runtime_java']}")
        if target["loader"] == "fabric":
            metadata = json.loads(archive.read("fabric.mod.json"))
            if metadata["id"] != target["mod_id"] or metadata["version"] != version:
                raise RuntimeError(f"Invalid Fabric metadata: {jar.name}")
            if metadata["depends"]["java"] != f">={minimum_java}":
                raise RuntimeError(f"Fabric Java requirement differs from class version: {jar.name}")
            game_range = metadata["depends"]["minecraft"]
            loader_requirement = metadata["depends"]["fabricloader"]
        elif target["loader"] == "paper":
            metadata = archive.read("plugin.yml").decode("utf-8")
            declared = re.search(r"(?m)^version:\s*['\"]?([^'\"\r\n]+)", metadata)
            if not declared or declared[1].strip() != version:
                raise RuntimeError(f"Invalid Paper version: {jar.name}")
            game_range = target["minecraft"]
            loader_requirement = None
        else:
            metadata_path = "META-INF/neoforge.mods.toml" if "META-INF/neoforge.mods.toml" in names else "META-INF/mods.toml"
            metadata = archive.read(metadata_path).decode("utf-8")
            if not re.search(r'modId\s*=\s*"' + re.escape(target["mod_id"]) + r'"', metadata) or not re.search(
                    r'version\s*=\s*"' + re.escape(version) + r'"', metadata):
                raise RuntimeError(f"Invalid mod metadata: {jar.name}")
            ranges = re.findall(r'versionRange\s*=\s*"([^"]+)"', metadata)
            game_range = ranges
            loader_requirement = re.search(r'loaderVersion\s*=\s*"([^"]+)"', metadata)[1]
        checksums = json.loads(archive.read(NATIVE_ROOT + "checksums.json"))
        canonical = json.loads((ROOT / "mod-common/src/main/resources" / NATIVE_ROOT / "checksums.json").read_text(encoding="utf-8"))
        if checksums != canonical or len(checksums) != 6:
            raise RuntimeError(f"Native helper checksum manifest differs: {jar.name}")
        for name, expected in checksums.items():
            if sha256(archive.read(NATIVE_ROOT + name)) != expected:
                raise RuntimeError(f"Native helper checksum mismatch in {jar.name}: {name}")
        return {"module": target["module"], "minecraft": target["minecraft"], "loader": target["loader"],
                "version": version, "mod_id": target.get("mod_id"), "metadata_game_range": game_range, "loader_requirement": loader_requirement,
                "minimum_class_java": minimum_java, "class_versions": sorted(majors),
                "helper_sha256": checksums, "jar": jar.name, "jar_sha256": sha256(jar.read_bytes()),
                "bytes": jar.stat().st_size, "validation": "build-tests-and-artifact",
                "gameplay_verified": False}


def git_info():
    def git(*args):
        result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None
    return {"commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain"))}


def source_fingerprint():
    digest = hashlib.sha256()
    files = set(ROOT.glob("*.gradle")) | set(ROOT.glob("*.properties"))
    for module in ["mod-common", *[target["module"] for target in targets()]]:
        directory = ROOT / module
        files.update(directory.glob("*.gradle"))
        files.update(directory.glob("*.properties"))
        files.update(directory.glob("gradle/wrapper/gradle-wrapper.properties"))
        for source_dir in (directory / "src/main/java", directory / "src/main/resources", directory / "src/test/java"):
            if source_dir.is_dir():
                files.update(path for path in source_dir.rglob("*") if path.is_file()
                             and (path.suffix in {".java", ".json", ".toml", ".yml", ".properties"} or path.name == "LICENSE"))
    files.update((ROOT / "helper").rglob("*.go"))
    files.update(ROOT.glob("helper/go.*"))
    files.update((ROOT / "helper/third_party").rglob("go.mod"))
    files.update((ROOT / "helper/third_party").rglob("go.sum"))
    files.update((ROOT / "helper/third_party").rglob("LICENSE"))
    files.update(ROOT.glob("scripts/*.py"))
    files.update(ROOT.glob("scripts/*.sh"))
    files.add(MATRIX)
    for path in sorted(files):
        if not path.is_file():
            continue
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build and verify all 15 Tailcat mods and Paper. Never skips tests.")
    parser.add_argument("--module", action="append", help="Select exact module(s); default is all 16.")
    parser.add_argument("--java-home", action="append", default=[], help="Additional JDK home; repeat as needed.")
    parser.add_argument("--list", action="store_true", help="Print matrix without running commands.")
    parser.add_argument("--dry-run", action="store_true", help="Preflight JDKs and show Gradle commands.")
    args = parser.parse_args(argv)
    matrix = targets()
    selected = [target for target in matrix if not args.module or target["module"] in args.module]
    if args.module and set(args.module) - {target["module"] for target in matrix}:
        parser.error("Unknown --module")
    if args.list:
        print(json.dumps(selected, ensure_ascii=False, indent=2))
        return 0
    required = {target["build_java"] for target in selected} | {target["runtime_java"] for target in selected}
    # Shared contracts run with a Java 17 toolchain, regardless of selected game version.
    required.add(17)
    jdks = find_jdks(required, args.java_home)
    common = {"module": "mod-common", "wrapper": ".", "build_java": max(required)}
    if args.dry_run:
        for target, tasks in [(common, ["test"]), *[(t, ["build"]) for t in selected]]:
            print(subprocess.list2cmdline(gradle_command(target, jdks, tasks)))
        return 0
    started = time.monotonic()
    build_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_dir = ROOT / "artifacts" / ("build-" + build_id)
    run_dir.mkdir(parents=True, exist_ok=False)
    source_hash = source_fingerprint()
    with (run_dir / "preparation-tests.log").open("w", encoding="utf-8") as output:
        checks = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts",
                                 "-p", "test_*.py", "-v"], cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    if checks.returncode:
        raise RuntimeError(f"Preparation script tests failed: {run_dir / 'preparation-tests.log'}")
    run_gradle(common, jdks, ["test", "--rerun-tasks"], run_dir / "mod-common.log")
    artifacts = []
    for target in selected:
        version = mod_version(target["module"])
        run_gradle(target, jdks, ["build"], run_dir / (target["module"] + ".log"))
        jar = runtime_jar_path(target, version)
        if not jar.is_file():
            raise RuntimeError(f"Expected runtime JAR not found: {jar}")
        entry = inspect_jar(jar, target, version)
        output_name = artifact_name(target, version)
        entry["jar"] = output_name
        entry["build_id"] = build_id
        artifacts.append((jar, entry, output_name))
        print(f"Verified {jar.name}: Java {entry['minimum_class_java']}", flush=True)
    if source_fingerprint() != source_hash:
        raise RuntimeError("Build sources changed during the run. Dist was not updated; rebuild a consistent source tree.")
    manifest = {"schema": 1, "build_id": build_id, "source": {**git_info(), "sha256": source_hash},
                "full_matrix": len(selected) == len(matrix), "common_contract_tests": "passed",
                "preparation_script_tests": "passed",
                "elapsed_seconds": round(time.monotonic() - started, 2),
                "artifacts": [entry for _, entry, _ in artifacts]}
    (run_dir / "release-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    for jar, _, output_name in artifacts:
        temporary = dist / (output_name + ".pending")
        shutil.copy2(jar, temporary)
        temporary.replace(dist / output_name)
    # A partial build never replaces the full release manifest.
    name = "release-manifest.json" if manifest["full_matrix"] else f"release-manifest-{build_id}.json"
    shutil.copy2(run_dir / "release-manifest.json", dist / name)
    print(f"Verified and copied {len(artifacts)} artifacts to {dist}. Manifest: {dist / name}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Build stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
