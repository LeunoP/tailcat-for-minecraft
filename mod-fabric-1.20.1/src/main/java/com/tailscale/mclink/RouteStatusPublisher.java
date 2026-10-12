package com.tailscale.mclink;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import io.netty.buffer.Unpooled;

final class RouteStatusPublisher {
    static final Identifier CHANNEL = new Identifier("mclink", "connection_status");
    private static final int HEARTBEAT_PACKET_TYPE = 1;
    private static final int SNAPSHOT_PACKET_TYPE = 0;
    private RouteStatusPublisher() {}

    static synchronized void registerHeartbeatReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL,
                (server, player, handler, buffer, responseSender) -> {
                    if (!buffer.isReadable() || buffer.readUnsignedByte() != HEARTBEAT_PACKET_TYPE) return;
                    int length = buffer.readableBytes();
                    int marker = length > 0 ? buffer.readUnsignedByte() : -1;
                    if (RouteStatusHeartbeat.isValid(length, marker))
                        RouteStatusLiveness.heartbeat(player.getUuid());
                });
    }

    static void sendHeartbeat(net.minecraft.client.MinecraftClient client) {
        if (client.player != null && client.getNetworkHandler() != null && RouteStatusHeartbeatClock.shouldSend()) {
            PacketByteBuf payload = new PacketByteBuf(Unpooled.buffer(2));
            payload.writeByte(HEARTBEAT_PACKET_TYPE);
            payload.writeByte(RouteStatusHeartbeat.MARKER);
            ClientPlayNetworking.send(CHANNEL, payload);
        }
    }

    static void receive(Object connection, byte[] bytes) {
        try {
            RouteStatusCache.accept(connection, bytes);
        } catch (IOException | RuntimeException invalid) {
            // Invalid messages do not renew the last validated snapshot.
        }
    }

    static void publish(MinecraftServer server, ToIntFunction<SocketAddress> routeForAddress) {
        RouteStatusLiveness.retainPlayers(server.getPlayerManager().getPlayerList().stream()
                .map(player -> player.getUuid()).toList());
        List<RouteStatus.PlayerRoute> routes = server.getPlayerManager().getPlayerList().stream()
                .map(player -> RouteStatusLiveness.playerRoute(player.getUuid(), player.getName().getString(),
                        routeForAddress.applyAsInt(player.networkHandler.getConnectionAddress()), Math.max(-1, player.pingMilliseconds), RouteStatusHostRegistry.isHost(server, player.getName().getString())))
                .toList();
        send(server.getPlayerManager().getPlayerList(), routes);
    }

    static void clear(MinecraftServer server) {
        RouteStatusLiveness.clear();
        send(server.getPlayerManager().getPlayerList(), List.of());
    }

    private static void send(List<ServerPlayerEntity> recipients, List<RouteStatus.PlayerRoute> routes) {
        for (byte[] snapshot : RouteStatus.encodePackets(routes)) {
            for (ServerPlayerEntity player : recipients) {
                PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer(snapshot.length + 1));
                buffer.writeByte(SNAPSHOT_PACKET_TYPE);
                buffer.writeBytes(snapshot);
                ServerPlayNetworking.send(player, CHANNEL, buffer);
            }
        }
    }
}
