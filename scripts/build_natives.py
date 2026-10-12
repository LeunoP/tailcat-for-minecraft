"""Build and synchronize the six embedded helpers using a supplied Tailscale Go toolchain."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TARGETS = [(system, arch) for system in ("windows", "darwin", "linux") for arch in ("amd64", "arm64")]
RESOURCE_ROOTS = sorted({module / "src/main/resources/assets/mclink/native"
                         for module in [*ROOT.glob("mod-*"), ROOT / "plugin-paper"]
                         if (module / "src/main/resources/assets/mclink/native").is_dir()})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go", type=Path, required=True, help="Patched Tailscale Go 1.26.5 executable")
    options = parser.parse_args()
    executable = options.go.resolve(strict=True)
    env = dict(os.environ, GOROOT=str(executable.parent.parent), CGO_ENABLED="0", GOMAXPROCS="2")
    version = subprocess.check_output([str(executable), "version"], env=env, text=True)
    if "go1.26.5 " not in version:
        raise SystemExit("Tailscale Go 1.26.5 is required")
    subprocess.run([str(executable), "test", "-p=2", "./..."], cwd=ROOT / "helper", env=env, check=True)
    subprocess.run([str(executable), "test", "-p=2", "-tags=ts_omit_ssh", "github.com/tailscale/tailcat"],
                   cwd=ROOT / "helper", env=env, check=True)
    outputs = []
    for system, arch in TARGETS:
        name = "mclink-helper.exe" if system == "windows" else "mclink-helper"
        relative = Path(f"{system}-{arch}") / name
        output = ROOT / "helper/bin" / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        pending = output.with_suffix(output.suffix + ".pending")
        print(f"Building helper {system}/{arch}", flush=True)
        subprocess.run([str(executable), "build", "-p=2", "-buildvcs=false", "-tags=ts_omit_ssh", "-trimpath",
                        "-ldflags=-s -w", "-o", str(pending), "./cmd/mclink-helper"],
                       cwd=ROOT / "helper", env=dict(env, GOOS=system, GOARCH=arch), check=True)
        pending.replace(output)
        outputs.append((relative, output))
    checksums = {relative.as_posix(): hashlib.sha256(output.read_bytes()).hexdigest() for relative, output in outputs}
    for resource_root in RESOURCE_ROOTS:
        for relative, output in outputs:
            destination = resource_root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(output, destination)
        (resource_root / "checksums.json").write_text(json.dumps(checksums, indent=2) + "\n", encoding="utf-8")
    print("All six helpers and checksums synchronized.", flush=True)


if __name__ == "__main__":
    main()
