package com.tailscale.mclink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class HelperIdentity {
    private HelperIdentity() {}
    public static String describe(Path executable) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(executable)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return "helperExecutable=" + executable.toAbsolutePath() + " sha256=" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable", error); }
    }
}
