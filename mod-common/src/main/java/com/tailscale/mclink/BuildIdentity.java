package com.tailscale.mclink;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class BuildIdentity {
    private BuildIdentity() {}
    public static Map<String, String> metadata(String module) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("module", module);
        if (module.startsWith("mod-")) {
            String[] parts = module.split("-", 3);
            metadata.put("loader", parts[1]); metadata.put("minecraft", parts[2]);
        }
        try (InputStream in = BuildIdentity.class.getResourceAsStream("/tailcat-build.properties")) {
            Properties properties = new Properties();
            if (in != null) properties.load(in);
            metadata.put("modVersion", properties.getProperty("version", "unknown"));
        } catch (Exception error) { metadata.put("modVersion", "unknown"); }
        try (InputStream in = BuildIdentity.class.getResourceAsStream("/assets/mclink/native/checksums.json")) {
            if (in != null) metadata.put("embeddedHelperChecksums", JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).toString());
        } catch (Exception error) { metadata.put("embeddedHelperChecksums", "unavailable"); }
        metadata.put("requestedHelperProtocol", "2");
        return metadata;
    }
}
