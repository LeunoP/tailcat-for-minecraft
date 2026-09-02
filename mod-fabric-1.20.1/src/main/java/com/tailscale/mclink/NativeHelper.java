package com.tailscale.mclink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class NativeHelper {
    private NativeHelper() {}

    public static Path extract() throws IOException {
        String override = System.getenv("MCLINK_HELPER");
        if (override != null && !override.isBlank()) {
            if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
                throw new IOException("MCLINK_HELPER is only available in a development environment");
            }
            Path executable = Path.of(override).toAbsolutePath();
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw new IOException("MCLINK_HELPER is not an executable file: " + executable);
            }
            return executable;
        }
        Platform platform = Platform.current();
        String relative = platform.resourceDirectory() + "/" + platform.executableName();
        String resource = "/assets/mclink/native/" + relative;
        JsonObject sums = checksums();
        if (!sums.has(relative) || !sums.get(relative).isJsonPrimitive()) throw new IOException("Missing checksum for " + relative);
        String expected = sums.get(relative).getAsString();
        String version = FabricLoader.getInstance().getModContainer("mclink")
                .or(() -> FabricLoader.getInstance().getModContainer("tailcat-for-minecraft"))
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.2.0");
        Path dir = FabricLoader.getInstance().getGameDir().resolve("tailcat-for-minecraft").resolve(version)
                .resolve(platform.resourceDirectory());
        Files.createDirectories(dir);
        Path destination = dir.resolve(platform.executableName());
        Path lockPath = dir.resolve("extract.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (Files.isRegularFile(destination) && sha256(destination).equalsIgnoreCase(expected)) return destination;
            Path temporary = Files.createTempFile(dir, "mclink-helper-", ".tmp");
            try {
                try (InputStream in = NativeHelper.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IOException("This mod JAR does not contain a helper for " + platform.resourceDirectory());
                    }
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!sha256(temporary).equalsIgnoreCase(expected)) {
                    throw new IOException("Packaged helper checksum mismatch for " + relative);
                }
                if (!platform.os().equals("windows") && !temporary.toFile().setExecutable(true, true)) {
                    throw new IOException("Could not make helper executable: " + temporary);
                }
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                return destination;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static JsonObject checksums() throws IOException {
        try (InputStream in = NativeHelper.class.getResourceAsStream("/assets/mclink/native/checksums.json")) {
            if (in == null) throw new IOException("Missing native checksums");
            return JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) { in.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
