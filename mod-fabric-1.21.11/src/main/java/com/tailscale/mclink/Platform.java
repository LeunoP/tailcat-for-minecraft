package com.tailscale.mclink;

import java.util.Locale;

public record Platform(String os, String arch) {
    public String resourceDirectory() { return os + "-" + arch; }
    public String executableName() { return os.equals("windows") ? "mclink-helper.exe" : "mclink-helper"; }

    public static Platform current() { return parse(System.getProperty("os.name"), System.getProperty("os.arch")); }

    public static Platform parse(String osName, String archName) {
        String osValue = osName.toLowerCase(Locale.ROOT);
        String os = osValue.contains("mac") || osValue.contains("darwin") ? "darwin"
                : osValue.contains("win") ? "windows"
                : osValue.contains("linux") ? "linux" : null;
        String archValue = archName.toLowerCase(Locale.ROOT);
        String arch = switch (archValue) {
            case "x86_64", "amd64", "x64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (os == null || arch == null) throw new IllegalStateException("Unsupported platform: " + osName + "/" + archName);
        return new Platform(os, arch);
    }
}
