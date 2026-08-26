#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/helper/bin"
RES="$ROOT/mod/src/main/resources/assets/mclink/native"
TSGO_REV=63ae404c8203317fd3c82d972e5dc8f0fcb425cb
mkdir -p "$OUT" "$RES"

export CGO_ENABLED=0
export GOMAXPROCS=${GOMAXPROCS:-2}

host_os=$(uname -s | tr '[:upper:]' '[:lower:]')
host_arch=$(uname -m)
case "$host_arch" in
  x86_64|amd64) host_arch=amd64 ;;
  arm64|aarch64) host_arch=arm64 ;;
  *) echo "unsupported build host architecture: $host_arch" >&2; exit 1 ;;
esac
[[ "$host_os" == darwin || "$host_os" == linux ]] || { echo "unsupported build host OS: $host_os" >&2; exit 1; }
toolchain="${XDG_CACHE_HOME:-$HOME/.cache}/mclink/tsgo/$TSGO_REV"
if [[ ! -x "$toolchain/bin/go" ]]; then
  archive="$toolchain.tar.gz"
  mkdir -p "$toolchain"
  curl --retry 3 -fsSL "https://github.com/tailscale/go/releases/download/build-$TSGO_REV/$host_os-$host_arch.tar.gz" -o "$archive"
  tar -xzf "$archive" -C "$toolchain" --strip-components=1
  rm -f "$archive"
fi
GO="$toolchain/bin/go"
[[ $($GO env GOVERSION) == go1.26.5 ]] || { echo "downloaded Tailscale Go version mismatch" >&2; exit 1; }
export GOROOT="$toolchain"
# Never reuse packages compiled by a different patched Tailscale Go revision.
export GOCACHE="${XDG_CACHE_HOME:-$HOME/.cache}/mclink/go-build/$TSGO_REV"
mkdir -p "$GOCACHE"

for target in windows/amd64 windows/arm64 darwin/amd64 darwin/arm64 linux/amd64 linux/arm64; do
  os=${target%/*}
  arch=${target#*/}
  dir="$os-$arch"
  name=mclink-helper
  [[ "$os" == windows ]] && name=mclink-helper.exe
  mkdir -p "$OUT/$dir" "$RES/$dir"
  (cd "$ROOT/helper" && GOOS="$os" GOARCH="$arch" "$GO" build -p=1 -buildvcs=false -tags=ts_omit_ssh -trimpath -ldflags='-s -w' -o "$OUT/$dir/$name" ./cmd/mclink-helper)
  cp "$OUT/$dir/$name" "$RES/$dir/$name"
done

python3 - "$RES" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
checksums = {}
for p in sorted(root.glob("*/mclink-helper*")):
    checksums[p.relative_to(root).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
(root / "checksums.json").write_text(json.dumps(checksums, indent=2) + "\n")
PY
