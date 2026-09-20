package com.tailscale.mclink;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerShareHandler {
    private static final Logger LOG = LoggerFactory.getLogger("Tailcat/Server");
    private static HelperProcess helperProcess;
    private static Path inviteFile;
    private static String currentInvite;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private ServerShareHandler() {}

    public static synchronized void onServerStarted(MinecraftServer server) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        final int port = server.getServerPort() > 0 ? server.getServerPort() : 25565;

        LOG.info("Initializing Tailcat tunnel for dedicated server on port {}...", port);

        try {
            List<String> args = List.of("host", "--target", "127.0.0.1:" + port);
            helperProcess = HelperProcess.start(args, event -> {
                if ("ready".equals(event.type()) && event.invite() != null) {
                    currentInvite = event.invite();
                    printBanner(currentInvite, port);
                    writeInviteFile(currentInvite);
                } else if ("error".equals(event.type())) {
                    LOG.error("Tailcat helper error: {} ({})", event.message(), event.code());
                }
            });

            helperProcess.ready(Duration.ofSeconds(20)).whenComplete((readyEvent, err) -> {
                if (err != null) {
                    LOG.error("Failed to start Tailcat helper: {}", err.getMessage());
                }
            });

        } catch (Exception e) {
            LOG.error("Could not start Tailcat server tunnel: {}", e.getMessage(), e);
            running.set(false);
        }
    }

    public static synchronized void onServerStopped(MinecraftServer server) {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        LOG.info("Stopping Tailcat server tunnel...");
        if (helperProcess != null) {
            try {
                helperProcess.close();
            } catch (Exception e) {
                LOG.warn("Error closing helper process: {}", e.getMessage());
            }
            helperProcess = null;
        }

        deleteInviteFile();
        currentInvite = null;
        LOG.info("Tailcat server tunnel stopped.");
    }

    private static void printBanner(String invite, int port) {
        LOG.info("==================================================================");
        LOG.info("  [Tailcat] Dedicated Server successfully shared via Tailcat!");
        LOG.info("  [Tailcat] Local Port: {}", port);
        LOG.info("  [Tailcat] Invitation Code (Share with friends):");
        LOG.info("  >>> {} <<<", invite);
        LOG.info("  [Tailcat] Saved to 'tailcat_invite.txt' in server directory");
        LOG.info("==================================================================");
    }

    private static void writeInviteFile(String invite) {
        try {
            inviteFile = Path.of("tailcat_invite.txt").toAbsolutePath();
            Files.writeString(inviteFile, invite, StandardCharsets.UTF_8);
            inviteFile.toFile().deleteOnExit();
        } catch (IOException e) {
            LOG.warn("Failed to write tailcat_invite.txt: {}", e.getMessage());
        }
    }

    private static void deleteInviteFile() {
        if (inviteFile != null) {
            try {
                Files.deleteIfExists(inviteFile);
            } catch (IOException ignored) {}
            inviteFile = null;
        }
    }

    public static String getCurrentInvite() {
        return currentInvite;
    }
}
