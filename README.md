# Tailcat for Minecraft

A Fabric 1.21.1 client mod that shares an integrated Minecraft world through
[Tailcat](https://github.com/tailscale/tailcat)'s
standard userspace WireGuard, magicsock direct NAT traversal, and DERP fallback.
It creates no TUN interface and requires no Tailscale installation. Java owns
Minecraft UI/process lifecycle; the bundled Go helper owns all networking and
encryption.

## Demo

[Watch Tailcat for Minecraft in action](tailcatmcdemo.mp4)

## Build

Requirements are JDK 21, `curl`, and `tar`. The helper uses the public
[Tailcat Go module](https://github.com/tailscale/tailcat). The native build
script downloads the required Tailscale Go toolchain:

```sh
scripts/build-natives.sh
./gradlew :mod:build
```

The normal JAR is written to `mod/build/libs/`. For Java-only development,
set `MCLINK_HELPER` to a locally built helper path. The native build produces
and checksums Windows, macOS (Intel and Apple Silicon), and Linux helpers.

## Use

The host pauses a single-player game and clicks **Share with Tailcat**, then
copies the `mcl1_...` invitation. The other player opens Multiplayer, clicks
**Connect with Tailcat**, pastes it, and connects. Opening remote sharing
publishes the integrated server using Minecraft's normal LAN behavior if it is
not already published.

The versioned invitation wraps an ephemeral Tailcat connection token. Possession
of that unguessable token authorizes access for the lifetime of the sharing
session. The helper allows only virtual TCP port 25565, binds join listeners
only to `127.0.0.1`, and caps concurrent streams.

### Local two-client development

To test with one Minecraft account, launch only the host with the explicitly
development-only offline-auth switch:

```sh
MCLINK_DEV_OFFLINE_AUTH=1 ./gradlew :mod:runClient
```

Launch the second client from another terminal with an isolated game directory
and a different offline username:

```sh
mkdir -p mod/run-client2
./gradlew :mod:runClient --args='--gameDir ../run-client2 --username Client2'
```

The switch is ignored outside Fabric's development environment and the server's
original online-mode setting is restored when Tailcat sharing stops. Never use
this bypass for real remote sharing.

## Release checklist

The build is unsigned by default. Public release artifacts must Authenticode-
sign both Windows executables, sign/notarize the two macOS executables, replace
the packaged files, regenerate `checksums.json`, generate complete third-party
notices, and then build the final JAR. Test separate machines and direct/DERP
paths as listed in the project brief.

Generate the dependency notices from the locked Go module graph (for example,
with `go-licenses report ./...`). Tailcat and Tailscale are BSD-3-Clause
licensed; Fabric Loader/API are runtime dependencies rather than embedded mod
contents, and Minecraft remains subject to Mojang's terms.
