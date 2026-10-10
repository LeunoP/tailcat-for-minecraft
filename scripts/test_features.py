import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FeatureSourceContracts(unittest.TestCase):
    def test_fabric_12111_join_title_has_opaque_alpha(self):
        screen = (ROOT / "mod-fabric-1.21.11/src/main/java/com/tailscale/mclink/JoinRemoteScreen.java").read_text(encoding="utf-8")
        self.assertRegex(screen, r"drawCenteredTextWithShadow\(textRenderer, title,.*0xFFFFFFFF\)")

    def test_translations_have_same_keys_and_arguments(self):
        native = ROOT / "mod-common/src/main/resources/assets/mclink/lang"
        languages = {name: json.loads((native / f"{name}.json").read_text(encoding="utf-8"))
                     for name in ("en_us", "ko_kr", "ja_jp")}
        reference = languages["en_us"]
        for name, messages in languages.items():
            self.assertEqual(set(reference), set(messages), name)
            for key, value in messages.items():
                self.assertEqual(re.findall(r"(?<!%)%(?:\d+\$)?[sd]", reference[key]),
                                 re.findall(r"(?<!%)%(?:\d+\$)?[sd]", value), f"{name}: {key}")
            for module in ROOT.glob("mod-*"):
                duplicate = module / "src/main/resources/assets/mclink/lang" / f"{name}.json"
                if duplicate.exists():
                    self.assertEqual(messages, json.loads(duplicate.read_text(encoding="utf-8")), f"{module.name}: {name}")

    def test_every_loader_has_administrator_operations(self):
        matrix = json.loads((ROOT / "scripts/support_matrix.json").read_text(encoding="utf-8"))["targets"]
        for target in matrix:
            if target["loader"] == "paper":
                continue
            module = ROOT / target["module"]
            commands = (module / "src/main/java/com/tailscale/mclink/TailcatCommands.java").read_text(encoding="utf-8")
            for operation in ("start", "stop", "restart"):
                self.assertRegex(commands, rf'literal\("{operation}"\)\s*\.requires\(', target["module"])
            state = (module / "src/main/java/com/tailscale/mclink/ScreenState.java").read_text(encoding="utf-8")
            self.assertNotIn('"도쿄"', state)
            self.assertNotIn('lower.contains("now using")', state)
            self.assertIn("tcpResourceLimit", state)
            self.assertIn("LastSuccessfulConnection.remember", state)

    def test_diagnostics_are_api_only_in_release(self):
        matrix = json.loads((ROOT / "scripts/support_matrix.json").read_text(encoding="utf-8"))["targets"]
        for target in matrix:
            module = ROOT / target["module"]
            java = module / "src/main/java/com/tailscale/mclink"
            if target["loader"] == "paper":
                plugin = (java / "TailcatPlugin.java").read_text(encoding="utf-8")
                self.assertNotIn('"diagnose"', plugin)
                self.assertIn("CompletableFuture<Path> diagnose()", plugin)
                self.assertNotIn("diagnose", (module / "src/main/resources/plugin.yml").read_text(encoding="utf-8"))
                continue
            for name in ("ShareScreen", "JoinRemoteScreen"):
                screen = (java / f"{name}.java").read_text(encoding="utf-8")
                self.assertNotIn("diagnose", screen, target["module"])
                self.assertNotIn("saveDiagnostics", screen, target["module"])
            commands = (java / "TailcatCommands.java").read_text(encoding="utf-8")
            self.assertNotIn('literal("diagnose")', commands, target["module"])
            state = (java / "ScreenState.java").read_text(encoding="utf-8")
            self.assertIn("public CompletableFuture<java.nio.file.Path> diagnose()", state)
        controller = (ROOT / "mod-common/src/main/java/com/tailscale/mclink/ServerTunnelController.java").read_text(encoding="utf-8")
        self.assertIn("diagnose()", controller)

    def test_host_improvements_are_applied_to_every_loader(self):
        matrix = json.loads((ROOT / "scripts/support_matrix.json").read_text(encoding="utf-8"))["targets"]
        for target in matrix:
            if target["loader"] == "paper":
                continue
            java = ROOT / target["module"] / "src/main/java/com/tailscale/mclink"
            screen = (java / "ShareScreen.java").read_text(encoding="utf-8")
            for key in ("waiting_players", "current_capacity", "next_share_capacity", "existing_lan_kept"):
                self.assertIn(f'"mclink.{key}"', screen, target["module"])
            self.assertIn("HostPreferences.SESSION.get()", screen)
            self.assertIn("HostPreferences.SESSION.remember(relayOnly, count)", screen)
            changed = re.search(r"(?:setChangedListener|setResponder)\(value -> \{([\s\S]*?)\n            \}\);", screen)
            self.assertIsNotNone(changed, target["module"])
            self.assertNotIn("rememberHostSettings", changed.group(1), target["module"])
            self.assertRegex(screen, r"getGuestPlayers\(\).isEmpty\(\)[\s\S]*?mclink.waiting_players")
            self.assertRegex(screen, r"server.getPlayer(?:List|Manager)\(\).getMaxPlayer(?:s|Count)\(\)")
            state = (java / "ScreenState.java").read_text(encoding="utf-8")
            self.assertIn("return isHosting() && publicationLease == null;", state)

    def test_last_invite_uses_visible_relay_option(self):
        matrix = json.loads((ROOT / "scripts/support_matrix.json").read_text(encoding="utf-8"))["targets"]
        for target in matrix:
            if target["loader"] == "paper":
                continue
            screen = (ROOT / target["module"] / "src/main/java/com/tailscale/mclink/JoinRemoteScreen.java").read_text(encoding="utf-8")
            getter = "isChecked" if "relayOnly.isChecked()" in screen else "selected"
            self.assertIn("var rememberedConnection = LastSuccessfulConnection.get();", screen, target["module"])
            self.assertIn(f"boolean forceRelay = relayOnly != null ? relayOnly.{getter}()", screen, target["module"])
            self.assertIn(": rememberedConnection != null && rememberedConnection.relayOnly();", screen, target["module"])
            self.assertIn(f"begin(previous.invite(), relayOnly.{getter}());", screen, target["module"])
            self.assertNotIn("previous.relayOnly()", screen, target["module"])

    def test_host_preferences_have_no_persistent_storage(self):
        source = (ROOT / "mod-common/src/main/java/com/tailscale/mclink/HostPreferences.java").read_text(encoding="utf-8")
        self.assertIn("new Settings(false, 8)", source)
        for storage_api in ("java.nio", "java.io", "java.util.prefs", "Files.", "Preferences."):
            self.assertNotIn(storage_api, source)

    def test_lan_client_mixins_are_not_loaded_on_dedicated_servers(self):
        for loader in ("fabric", "forge", "neoforge"):
            for version in ("1.20.1", "1.21.1", "1.21.11"):
                module = ROOT / f"mod-{loader}-{version}"
                config = json.loads((module / "src/main/resources/mclink.mixins.json").read_text(encoding="utf-8"))
                for name in ("LanPublicationAccessor", "LanListenersAccessor"):
                    self.assertIn(name, config["client"])
                    self.assertNotIn(name, config.get("mixins", []))

    def test_share_status_hides_derp_region_and_ping_prefix(self):
        matrix = json.loads((ROOT / "scripts/support_matrix.json").read_text(encoding="utf-8"))["targets"]
        for target in matrix:
            if target["loader"] == "paper":
                continue
            screen = (ROOT / target["module"] / "src/main/java/com/tailscale/mclink/ShareScreen.java").read_text(encoding="utf-8")
            self.assertNotIn("getRelayRegion()", screen, target["module"])
            self.assertNotIn('"MC " + latency', screen, target["module"])
            self.assertIn('latency + "ms"', screen, target["module"])


if __name__ == "__main__":
    unittest.main()
