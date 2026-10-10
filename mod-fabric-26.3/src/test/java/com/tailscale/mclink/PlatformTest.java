package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformTest {
    @Test void mapsMacArm() { assertEquals("darwin-arm64", Platform.parse("Mac OS X", "aarch64").resourceDirectory()); }
    @Test void mapsWindowsX64() { assertEquals("windows-amd64", Platform.parse("Windows 11", "amd64").resourceDirectory()); }
    @Test void mapsLinuxArm() { assertEquals("linux-arm64", Platform.parse("Linux", "arm64").resourceDirectory()); }
    @Test void rejectsUnsupported() { assertThrows(IllegalStateException.class, () -> Platform.parse("Plan 9", "mips")); }
}
