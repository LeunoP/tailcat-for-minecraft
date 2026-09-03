package com.tailscale.mclink;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public final class TailcatPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private PluginHelperProcess helperProcess;
    private Path inviteFile;
    private String currentInvite;
    private int currentPort;

    @Override
    public void onEnable() {
        currentPort = Bukkit.getPort() > 0 ? Bukkit.getPort() : 25565;

        getLogger().info("Initializing Tailcat tunnel for Paper/Spigot server on port " + currentPort + "...");

        try {
            Path dataFolder = getDataFolder().toPath();
            Files.createDirectories(dataFolder);

            List<String> args = List.of("host", "--target", "127.0.0.1:" + currentPort);
            helperProcess = PluginHelperProcess.start(dataFolder, getDescription().getVersion(), args, event -> {
                if ("ready".equals(event.type()) && event.invite() != null) {
                    currentInvite = event.invite();
                    printBanner(currentInvite, currentPort);
                    writeInviteFile(currentInvite);
                } else if ("error".equals(event.type())) {
                    getLogger().severe("Tailcat error: " + event.message() + " (" + event.code() + ")");
                }
            }, line -> getLogger().info("[Helper] " + line));

            helperProcess.ready(Duration.ofSeconds(20)).whenComplete((readyEvent, err) -> {
                if (err != null) {
                    getLogger().log(Level.SEVERE, "Failed to start Tailcat helper: " + err.getMessage(), err);
                }
            });

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not start Tailcat server tunnel: " + e.getMessage(), e);
        }

        if (getCommand("tailcat") != null) {
            getCommand("tailcat").setExecutor(this);
            getCommand("tailcat").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Stopping Tailcat server tunnel...");
        if (helperProcess != null) {
            try {
                helperProcess.close();
            } catch (Exception e) {
                getLogger().warning("Error closing helper process: " + e.getMessage());
            }
            helperProcess = null;
        }

        deleteInviteFile();
        currentInvite = null;
        getLogger().info("Tailcat server tunnel stopped.");
    }

    private void printBanner(String invite, int port) {
        getLogger().info("==================================================================");
        getLogger().info("  [Tailcat] Paper/Spigot Server successfully shared via Tailcat!");
        getLogger().info("  [Tailcat] Local Port: " + port);
        getLogger().info("  [Tailcat] Invitation Code (Share with friends):");
        getLogger().info("  >>> " + invite + " <<<");
        getLogger().info("  [Tailcat] Saved to 'tailcat_invite.txt' in server directory");
        getLogger().info("==================================================================");
    }

    private void writeInviteFile(String invite) {
        try {
            inviteFile = Path.of("tailcat_invite.txt").toAbsolutePath();
            Files.writeString(inviteFile, invite, StandardCharsets.UTF_8);
            inviteFile.toFile().deleteOnExit();
        } catch (IOException e) {
            getLogger().warning("Failed to write tailcat_invite.txt: " + e.getMessage());
        }
    }

    private void deleteInviteFile() {
        if (inviteFile != null) {
            try {
                Files.deleteIfExists(inviteFile);
            } catch (IOException ignored) {}
            inviteFile = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && "invite".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("tailcat.admin")) {
                sender.sendMessage(ChatColor.RED + "[Tailcat] 권한이 없습니다.");
                return true;
            }
            if (currentInvite != null) {
                sender.sendMessage(ChatColor.GREEN + "[Tailcat] " + ChatColor.WHITE + "초대 코드: " + ChatColor.YELLOW + currentInvite);
                sender.sendMessage(ChatColor.GRAY + "(채팅창의 코드를 복사하거나 서버 루트의 'tailcat_invite.txt' 파일을 확인하세요)");
            } else {
                sender.sendMessage(ChatColor.RED + "[Tailcat] 아직 초대 코드가 준비되지 않았거나 터널이 연결되지 않았습니다.");
            }
            return true;
        }

        // /tailcat or /tailcat status
        boolean isAdmin = sender.hasPermission("tailcat.admin");
        if (isAdmin) {
            sender.sendMessage(ChatColor.GOLD + "=== [Tailcat 연결 상태 (전체)] ===");
            for (Player player : Bukkit.getOnlinePlayers()) {
                sender.sendMessage(formatPlayerLine(player));
            }
        } else if (sender instanceof Player player) {
            sender.sendMessage(formatPlayerLine(player));
        } else {
            sender.sendMessage(ChatColor.GRAY + "콘솔에서는 모든 유저 정보를 확인하려면 관리자 권한이 필요합니다.");
        }
        return true;
    }

    private String formatPlayerLine(Player player) {
        int ping = player.getPing();
        String connType = resolveConnectionType(player);
        ChatColor typeColor = "다이렉트".equals(connType) ? ChatColor.AQUA : ChatColor.YELLOW;

        return ChatColor.GRAY + "[ " + ChatColor.GREEN + player.getName() + ChatColor.GRAY + " ] " +
               ChatColor.WHITE + ping + "ms " + ChatColor.GRAY + "/ " + typeColor + connType;
    }

    private String resolveConnectionType(Player player) {
        if (player.getAddress() != null) {
            String ip = player.getAddress().getAddress().getHostAddress();
            if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
                return "다이렉트";
            }
            if (!ip.startsWith("100.") && !ip.startsWith("fd7a:")) {
                return "다이렉트";
            }
        }
        return "릴레이";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("invite", "status");
        }
        return Collections.emptyList();
    }
}
