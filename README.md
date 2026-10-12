# Tailcat for Minecraft

[English](README.md) | [한국어](README.ko.md)

> [!NOTE]
> * Official upstream project: [tailscale/tailcat-for-minecraft](https://github.com/tailscale/tailcat-for-minecraft)
> * This mod was ported and written with AI, based on the original prototype. Yes, all of it.
> * This project uses [Tailscale Tailcat](https://github.com/tailscale/tailcat) to connect Minecraft worlds with friends over peer-to-peer connections or DERP relays, without port forwarding or an external virtual LAN or VPN setup.

No separate Tailscale app or TUN adapter permissions are required. A userspace Go helper bundled with the mod or plugin handles direct WireGuard connections using NAT traversal, as well as DERP relay traffic.

---

## Features

* One-click single-player world sharing: select **Share with Tailcat** from the pause menu to create an invite code such as `mcl1_...`.
* Simple multiplayer joining: select **Connect with Tailcat** from the multiplayer screen and enter the invite code.
* Dedicated server and Paper plugin support: run a background tunnel on Fabric, Forge, NeoForge, Paper, and Spigot servers, in addition to hosting a single-player world.
* Korean in-game UI: buttons, dialogs, status messages, and tooltips are available in Korean.
* Managed helper lifecycle: the `mclink-helper` process is shut down when the game or server exits, and routine internal communication is kept out of the server console.

---

## Supported versions and platforms (v0.3.1)

Build artifacts are available from [Releases](https://github.com/LeunoP/tailcat-for-minecraft/releases) or the repository's `dist/` directory.

### Mods (client and dedicated server)

| Minecraft version | Fabric | Forge | NeoForge | Java |
|:---:|:---:|:---:|:---:|:---:|
| 1.21.11 | Supported | Supported | Supported | Java 21+ |
| 1.20.1 | Supported | Supported | Supported (LegacyForge) | Java 17+ |
| 1.21.1 | Supported | Supported | Supported | Java 21+ |
| 26.2 | Supported | Supported | Supported | Java 25+ |
| 26.3 | Supported | Supported | Supported (beta) | Java 25+ |

### Server plugin (Paper / Purpur / Spigot)

| Type | Supported versions | Java | Artifact |
|:---:|:---:|:---:|:---:|
| Paper plugin | Compatible with 1.17 through 26.2+ | Java 17+ | `tailcat-for-minecraft-paper-plugin-0.3.1.jar` |

---

## How to use

### 1. Host a single-player world

1. Join a single-player world.
2. Press `Esc` to open the pause menu.
3. Select **Share with Tailcat**.
4. Copy the generated `mcl1_...` invite code and send it to the players you want to invite.

Multiple players can use the same invite code. If you upgrade the host to a version that supports separate Disco keys, generate and share a new code from the host instead of reusing the old one.

### 2. Join as a guest

1. Select **Multiplayer** from the main menu.
2. Select **Connect with Tailcat** at the bottom of the screen.
3. Paste the invite code and select **Connect**.

### 3. Use with a dedicated server or Paper plugin

1. Put the appropriate JAR in the server's `mods` directory (modded server) or `plugins` directory (Paper server), then start the server.
2. By default, the Tailcat tunnel starts with the server. Find the invite code in `tailcat_invite.txt` in the server root, or use `/tailcat invite` as an administrator.
3. Join from the client using the guest steps above.

---

## Server commands

* `/tailcat invite` (administrators only): show the active server's invite code, which can be copied from chat.
* `/tailcat start`, `/tailcat stop`, `/tailcat restart` (dedicated server and Paper administrators only): start, stop, or restart sharing without restarting the server. Restart waits for the previous helper process to shut down.
* `/tailcat status` or `/tailcat`:
  * Operators (OP) see the list of users connected through the Tailcat tunnel and their latency in milliseconds.
  * Other players see their own Tailcat connection status and ping.

---

## Server configuration

The dedicated mod server creates `config/tailcat-server.json` on first launch. Paper creates `plugins/Tailcat/tailcat-server.json`.

```json
{
  "autoShare": true,
  "relayOnly": false,
  "tcpConnectionLimit": 256,
  "startupTimeoutSeconds": 20,
  "language": "en_us"
}
```

`autoShare` controls whether sharing starts automatically when the server starts. After changing other tunnel settings, apply them with `/tailcat restart`. Supported languages are `en_us`, `ko_kr`, and `ja_jp`. Invalid values produce a warning and use defaults without overwriting the original configuration file.

`tcpConnectionLimit` limits concurrent TCP connections, including server queries. It is not a player limit. The dedicated server player cap is set by Minecraft's `max-players`. Single-player sharing separately supports 2 to 64 players, including the host. The server must accept helper connections at `127.0.0.1:<server port>`.

The sharing screen distinguishes the current server capacity from the capacity for the next share. A valid host capacity and the relay-only option are remembered only for the current game session and are not saved to a file. After restarting the game, the defaults are restored: 8 players and relay-only disabled. When nobody is connected, the screen shows a waiting state instead of a connection route. If ordinary LAN sharing was already open, stopping Tailcat sharing leaves that LAN session active.

Diagnostics are available only through the developer API. Regular releases do not include a diagnostics save button or a `diagnose` command. When the API is explicitly called, the game writes files to `tailcat-for-minecraft/diagnostics` in the game directory, and Paper writes them to `plugins/Tailcat/diagnostics`. Minecraft ping and the tunnel status query's round-trip time are different measurements. Routes, regions, and latency that were not measured are not estimated.

---

## Build (developers)

The build matrix contains 15 mods and one Paper plugin, for 16 targets total, managed in `scripts/support_matrix.json`. JDK 17, 21, and 25, plus Python 3.10 or later, are required. JDKs are detected automatically or can be selected with `--java-home`.

If you change the helper, first run `python scripts/build_natives.py --go <path to go executable>` with the Tailscale Go 1.26.5 toolchain. The script runs Go tests and updates all six bundled helpers and their checksums. Adjustments to the pinned Tailcat source are documented in `helper/third_party/tailcat/LOCAL_CHANGES.md`.

```powershell
python scripts/build_all.py --list
python scripts/build_all.py --dry-run
python scripts/build_all.py
# Build or check one module
python scripts/build_all.py --module mod-fabric-26.3
```

Do not skip the shared contract tests or module build tests. The build checks JAR metadata, Java class versions, and SHA-256 checksums for all six bundled helpers. It updates `dist/` only after every selected target passes. Build logs are stored in `artifacts/build-<ID>/`, and the full release manifest is written to `dist/manifests/release-manifest.json`. Partial builds get a separate manifest and do not overwrite the full release manifest. A successful build does not by itself confirm in-game compatibility.

Existing Gradle commands are also supported. Forge 1.x uses its module's Gradle 8.8 wrapper, Forge 26.2 uses the root Gradle 9.5.1 wrapper, and 26.3 modules use their own Gradle 9.7.1 wrappers.

```bash
# Fabric modules
./gradlew -p mod-fabric-1.20.1 build
./gradlew -p mod-fabric-1.21.1 build
./gradlew -p mod-fabric-1.21.11 build
./gradlew :mod-fabric-26.2:build
cd mod-fabric-26.3 && ./gradlew build && cd ..

# Forge modules
cd mod-forge-1.20.1 && ./gradlew build && cd ..
cd mod-forge-1.21.1 && ./gradlew build && cd ..
cd mod-forge-1.21.11 && ./gradlew build && cd ..
./gradlew -p mod-forge-26.2 build
cd mod-forge-26.3 && ./gradlew build && cd ..

# NeoForge modules
./gradlew -p mod-neoforge-1.20.1 build
./gradlew -p mod-neoforge-1.21.1 build
./gradlew -p mod-neoforge-1.21.11 build
./gradlew -p mod-neoforge-26.2 build
cd mod-neoforge-26.3 && ./gradlew build && cd ..

# Paper plugin
./gradlew -p plugin-paper build
```

### Isolated Prism launch check

`scripts/test_instances.py` copies only the configuration, mods, and settings specified for a source instance into a new `tailcat-test-*` instance. It does not copy or clear existing worlds or logs. It uses only a JAR whose Minecraft version and loader match exactly and whose hash matches the full release manifest.

```powershell
# Replace the source name with the actual folder name under Prism's instances directory.
python scripts/test_instances.py --source "26.3-fabric" --module mod-fabric-26.3
# Launch only when --launch is specified. Requires psutil.
python scripts/test_instances.py --source "26.3-fabric" --module mod-fabric-26.3 --launch --close-owned
python -m unittest discover -s scripts -p test_preparation.py -v
```

Process-closing options apply only to a game PID created during the current run whose working directory also matches. The script tries a normal window close first. Forced termination is allowed only with `--force-stop-owned`. Test instances, logs, and result JSON files are not deleted. Resource initialization logs alone do not establish that the title screen, sharing, or joining works.

---

## License

* This project is licensed under BSD-3-Clause. See [LICENSE](LICENSE).
* Tailcat and Tailscale are trademarks of Tailscale Inc. and open-source software.
* Minecraft is a registered trademark of Mojang AB and Microsoft.
