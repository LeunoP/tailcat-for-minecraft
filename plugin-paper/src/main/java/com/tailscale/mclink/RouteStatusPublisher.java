package com.tailscale.mclink;

import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class RouteStatusPublisher {
    static final String CHANNEL = "mclink:connection_status";
    private final JavaPlugin plugin;

    RouteStatusPublisher(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void publish(List<Player> recipients, ToIntFunction<SocketAddress> routeForAddress) {
        List<RouteStatus.PlayerRoute> routes = recipients.stream()
                .map(player -> new RouteStatus.PlayerRoute(player.getName(),
                        routeForAddress.applyAsInt(player.getAddress()), Math.max(-1, player.getPing())))
                .toList();
        send(recipients, routes);
    }

    void clear() {
        send(List.copyOf(plugin.getServer().getOnlinePlayers()), List.of());
    }

    private void send(List<Player> recipients, List<RouteStatus.PlayerRoute> routes) {
        for (byte[] snapshot : RouteStatus.encodePackets(routes)) {
            byte[] payload = new byte[snapshot.length + 1];
            payload[0] = 0;
            System.arraycopy(snapshot, 0, payload, 1, snapshot.length);
            for (Player player : recipients) player.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }
}
