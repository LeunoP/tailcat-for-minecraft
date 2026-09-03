package com.tailscale.mclink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

public final class PluginNativeHelper {
    private PluginNativeHelper() {}

    public static Path extract(Path dataFolder, String version) throws IOException {
        String override = System.getenv("MCLINK_HELPER");
        if (override != null && !override.isBlank()) {
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
        if (!sums.has(relative) || !sums.get(relative).isJsonPrimitive()) {
            throw new IOException("Missing checksum for " + relative);
        }
        String expected = sums.get(relative).getAsString();

        Path dir = dataFolder.resolve("native").resolve(version).resolve(platform.resourceDirectory());
        Files.createDirectories(dir);
        Path destination = dir.resolve(platform.executableName());
        Path lockPath = dir.resolve("extract.lock");

        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (Files.isRegularFile(destination) && sha256(destination).equalsIgnoreCase(expected)) {
                return destination;
            }
            Path temporary = Files.createTempFile(dir, "mclink-helper-", ".tmp");
            try {
                try (InputStream in = PluginNativeHelper.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IOException("This plugin JAR does not contain a helper for " + platform.resourceDirectory());
                    }
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!sha256(temporary).equalsIgnoreCase(expected)) {
                    throw new IOException("Packaged helper checksum mismatch for " + relative);
                }
                ensureExecutable(temporary);
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            ensureExecutable(destination);
            return destination;
        }
    }

    private static void ensureExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows does not use POSIX file permissions
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject checksums() throws IOException {
        try (InputStream in = PluginNativeHelper.class.getResourceAsStream("/assets/mclink/native/checksums.json")) {
            if (in == null) throw new IOException("Missing checksums.json in plugin");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
