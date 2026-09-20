package com.tailscale.mclink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

public final class NativeHelper {
    private NativeHelper() {}

    public static Path extract() throws IOException {
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
        if (!sums.has(relative) || !sums.get(relative).isJsonPrimitive()) throw new IOException("Missing checksum for " + relative);
        String expected = sums.get(relative).getAsString();
        String version = "0.2.0";
        Path dir = FMLPaths.GAMEDIR.get().resolve("tailcat-for-minecraft").resolve(version)
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
                setExecutable(temporary);
                try {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                setExecutable(destination);
                return destination;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static JsonObject checksums() throws IOException {
        try (InputStream in = NativeHelper.class.getResourceAsStream("/assets/mclink/native/checksums.json")) {
            if (in == null) throw new IOException("Packaged checksums.json is missing");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new IOException("Failed computing SHA-256 for " + file, error);
        }
    }

    private static void setExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (Windows)
        }
    }
}
