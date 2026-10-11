package com.tailscale.mclink;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

final class RouteStatusPublisher {
    private static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath("mclink", "connection_status"))
            .networkProtocolVersion(1)
            .optional()
            .simpleChannel();

    static {
        CHANNEL.messageBuilder(byte[].class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((bytes, buffer) -> buffer.writeBytes(bytes))
                .decoder(buffer -> {
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    return bytes;
                })
                .consumerMainThread((bytes, context) -> receive(context.getConnection(), bytes))
                .add();
    }

    private RouteStatusPublisher() {}
    static void initialize() { /* Trigger channel registration on client-only installations. */ }

    private static void receive(Object connection, byte[] bytes) {
        try { RouteStatusCache.accept(connection, bytes); }
        catch (IOException | RuntimeException invalid) { /* Keep the last validated snapshot until it expires. */ }
    }

    static synchronized void publish(MinecraftServer server, ToIntFunction<SocketAddress> routeForAddress) {
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        List<RouteStatus.PlayerRoute> routes = players.stream()
                .map(player -> new RouteStatus.PlayerRoute(player.getName().getString(),
                        routeForAddress.applyAsInt(player.connection.getRemoteAddress()), Math.max(-1, player.connection.latency())))
                .toList();
        send(players, routes);
    }

    static synchronized void clear(MinecraftServer server) {
        send(List.copyOf(server.getPlayerList().getPlayers()), List.of());
    }

    private static void send(List<ServerPlayer> recipients, List<RouteStatus.PlayerRoute> routes) {
        for (byte[] snapshot : RouteStatus.encodePackets(routes)) {
            for (ServerPlayer player : recipients) CHANNEL.send(snapshot, PacketDistributor.PLAYER.with(player));
        }
    }
}
