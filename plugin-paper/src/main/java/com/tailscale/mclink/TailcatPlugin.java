package com.tailscale.mclink;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

public final class TailcatPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private ServerTunnelController<PluginHelperProcess> tunnel;
    private RouteStatusPublisher routeStatusPublisher;
    private volatile boolean enabled;

    @Override
    public void onEnable() {
        enabled = true;
        Path data = getDataFolder().toPath();
        String version = getDescription().getVersion();
        routeStatusPublisher = new RouteStatusPublisher(this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, RouteStatusPublisher.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, RouteStatusPublisher.HEARTBEAT_CHANNEL,
                (channel, player, message) -> RouteStatusLiveness.heartbeat(player.getUniqueId()));
        tunnel = new ServerTunnelController<>(this::onMainThread,
                (args, relayOnly, events, logs) -> PluginHelperProcess.start(data, version, args, events, logs, relayOnly),
                PluginHelperProcess::isAlive, PluginHelperProcess::closed, process -> process.ready(Duration.ofSeconds(120)),
                data.resolve("tailcat-server.json"), Path.of("tailcat_invite.txt"), data.resolve("diagnostics"),
                BuildIdentity.metadata("plugin-paper"), getLogger()::info, getLogger()::warning);
        tunnel.onStop(owns -> {
            onMainThread(routeStatusPublisher::clear);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (owns.test(player.getAddress())) player.kickPlayer(ServerMessages.text(tunnel.config().language(), "mclink.stopped_message"));
            }
        });
        if (getCommand("tailcat") != null) {
            getCommand("tailcat").setExecutor(this);
            getCommand("tailcat").setTabCompleter(this);
        }
        tunnel.initialize(Bukkit.getPort());
        Bukkit.getScheduler().runTaskTimer(this, this::refreshRouteStatuses, 20L, 20L);
    }

    @Override
    public void onDisable() {
        enabled = false;
        if (routeStatusPublisher != null) routeStatusPublisher.clear();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, RouteStatusPublisher.CHANNEL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, RouteStatusPublisher.HEARTBEAT_CHANNEL);
        if (tunnel != null) tunnel.close();
    }

    private void refreshRouteStatuses() {
        ServerTunnelController<PluginHelperProcess> current = tunnel;
        if (!enabled || routeStatusPublisher == null) return;
        if (current == null || current.invite() == null) {
            routeStatusPublisher.clear();
            return;
        }
        routeStatusPublisher.publish(List.copyOf(Bukkit.getOnlinePlayers()), address ->
                RouteStatus.encode(current.owns(address), current.route(address)));
    }

    public java.util.concurrent.CompletableFuture<Path> diagnose() {
        var current = tunnel;
        if (!enabled || current == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("Tailcat is not enabled"));
        }
        return current.diagnose();
    }

    private void onMainThread(Runnable action) {
        if (!enabled) return;
        if (Bukkit.isPrimaryThread()) { action.run(); return; }
        try {
            Bukkit.getScheduler().runTask(this, () -> { if (enabled) action.run(); });
        } catch (org.bukkit.plugin.IllegalPluginAccessException error) {
            if (enabled) getLogger().warning("Could not schedule Tailcat update: " + error.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String currentInvite = tunnel == null ? null : tunnel.invite();
        if (args.length > 0 && List.of("start", "stop", "restart").contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
            if (!sender.hasPermission("tailcat.admin")) {
                sender.sendMessage(ChatColor.RED + "[Tailcat] " + message("no_permission")); return true;
            }
            String action = args[0].toLowerCase(java.util.Locale.ROOT);
            java.util.concurrent.CompletableFuture<?> operation = switch (action) {
                case "start" -> tunnel.start(Bukkit.getPort());
                case "stop" -> tunnel.stop();
                case "restart" -> tunnel.restart(Bukkit.getPort());
                default -> throw new IllegalArgumentException("Unknown Tailcat operation: " + action);
            };
            operation.whenComplete((result, error) -> onMainThread(() -> sender.sendMessage(
                    (error == null ? ChatColor.GREEN : ChatColor.RED) +
                    ServerMessages.response(tunnel.config().language(), action, error == null, result))));
            return true;
        }
        if (args.length > 0 && "invite".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("tailcat.admin")) {
                sender.sendMessage(ChatColor.RED + "[Tailcat] " + message("no_permission"));
                return true;
            }
            if (currentInvite != null) {
                sender.sendMessage(ChatColor.GREEN + message("invite_label") + ChatColor.YELLOW + currentInvite);
                sender.sendMessage(ChatColor.GRAY + message("invite_hint"));
            } else {
                sender.sendMessage(ChatColor.RED + message("inactive"));
            }
            return true;
        }

        boolean isAdmin = sender.hasPermission("tailcat.admin");
        if (isAdmin) {
            sender.sendMessage(ChatColor.GOLD + message("status_title"));
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                sender.sendMessage(formatPlayerLine(player));
                count++;
            }
            if (count == 0) {
                sender.sendMessage(ChatColor.GRAY + message("no_players"));
            }
        } else if (sender instanceof Player player) {
            sender.sendMessage(formatPlayerLine(player));
        } else {
            sender.sendMessage(ChatColor.GRAY + message("console_permission"));
        }
        return true;
    }

    private String message(String key) { return ServerMessages.text(tunnel.config().language(), "mclink.server." + key); }

    private String formatPlayerLine(Player player) {
        int ping = player.getPing();
        int status = tunnel == null ? RouteStatus.NOT_TAILCAT : tunnel.connectionStatus(player.getAddress());
        status = RouteStatusLiveness.effectiveStatus(player.getUniqueId(), status, false);
        String route = RouteStatus.commandLabel(status, tunnel == null ? "en_us" : tunnel.config().language());

        return ChatColor.GRAY + "[ " + ChatColor.GREEN + player.getName() + ChatColor.GRAY + " ] " +
               routeColor(status) + route + " " + ChatColor.WHITE + ping + "ms";
    }

    private ChatColor routeColor(int status) {
        return switch (status) {
            case RouteStatus.NOT_TAILCAT -> ChatColor.BLUE;
            case RouteStatus.DIRECT -> ChatColor.GREEN;
            case RouteStatus.RELAY -> ChatColor.GOLD;
            case RouteStatus.DISCONNECTED -> ChatColor.RED;
            default -> ChatColor.YELLOW;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
            return (sender.hasPermission("tailcat.admin") ? List.of("invite", "status", "start", "stop", "restart") : List.of("status"))
                    .stream().filter(name -> name.startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }
}
