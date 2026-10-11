package com.tailscale.mclink;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import io.netty.buffer.Unpooled;

final class RouteStatusPublisher {
    static final Identifier CHANNEL = new Identifier("mclink", "connection_status");
    private RouteStatusPublisher() {}

    static void receive(Object connection, byte[] bytes) {
        try {
            RouteStatusCache.accept(connection, bytes);
        } catch (IOException | RuntimeException invalid) {
            // Invalid messages do not renew the last validated snapshot.
        }
    }

    static void publish(MinecraftServer server, ToIntFunction<SocketAddress> routeForAddress) {
        List<RouteStatus.PlayerRoute> routes = server.getPlayerManager().getPlayerList().stream()
                .map(player -> new RouteStatus.PlayerRoute(player.getName().getString(),
                        routeForAddress.applyAsInt(player.networkHandler.getConnectionAddress()), Math.max(-1, player.pingMilliseconds)))
                .toList();
        send(server.getPlayerManager().getPlayerList(), routes);
    }

    static void clear(MinecraftServer server) {
        send(server.getPlayerManager().getPlayerList(), List.of());
    }

    private static void send(List<ServerPlayerEntity> recipients, List<RouteStatus.PlayerRoute> routes) {
        for (byte[] snapshot : RouteStatus.encodePackets(routes)) {
            for (ServerPlayerEntity player : recipients) {
                PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer(snapshot.length + 1));
                buffer.writeByte(0);
                buffer.writeBytes(snapshot);
                ServerPlayNetworking.send(player, CHANNEL, buffer);
            }
        }
    }
}
