package com.tailscale.mclink;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

final class RouteStatusPublisher {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("mclink", "connection_status"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

    static {
        CHANNEL.messageBuilder(SnapshotPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((payload, buffer) -> buffer.writeBytes(payload.bytes()))
                .decoder(buffer -> {
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    return new SnapshotPayload(bytes);
                })
                .consumerMainThread((payload, context) -> {
                    receive(context.get().getNetworkManager(), payload.bytes());
                    context.get().setPacketHandled(true);
                })
                .add();
        CHANNEL.messageBuilder(HeartbeatPayload.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder((payload, buffer) -> buffer.writeByte(RouteStatusHeartbeat.MARKER))
                .decoder(buffer -> {
                    int length = buffer.readableBytes();
                    int marker = length > 0 ? buffer.readUnsignedByte() : -1;
                    if (!RouteStatusHeartbeat.isValid(length, marker))
                        throw new IllegalArgumentException("Invalid Tailcat heartbeat payload");
                    return new HeartbeatPayload();
                })
                .consumerMainThread((payload, context) -> {
                    if (context.get().getSender() != null)
                        RouteStatusLiveness.heartbeat(context.get().getSender().getUUID());
                    context.get().setPacketHandled(true);
                })
                .add();
    }

    private RouteStatusPublisher() {}
    static void initialize() { /* Trigger channel registration on client-only installations. */ }

    static void sendHeartbeat() {
        if (RouteStatusHeartbeatClock.shouldSend()
                && net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            CHANNEL.sendToServer(new HeartbeatPayload());
        }
    }

    private static void receive(Object connection, byte[] bytes) {
        try { RouteStatusCache.accept(connection, bytes); }
        catch (IOException | RuntimeException invalid) { /* Keep the last validated snapshot until it expires. */ }
    }

    static synchronized void publish(MinecraftServer server, ToIntFunction<SocketAddress> routeForAddress) {
        RouteStatusLiveness.retainPlayers(server.getPlayerList().getPlayers().stream()
                .map(player -> player.getUUID()).toList());
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        List<RouteStatus.PlayerRoute> routes = players.stream()
                .map(player -> RouteStatusLiveness.playerRoute(player.getUUID(), player.getName().getString(),
                        routeForAddress.applyAsInt(player.connection.getRemoteAddress()), Math.max(-1, player.latency), RouteStatusHostRegistry.isHost(server, player.getName().getString())))
                .toList();
        send(players, routes);
    }

    static synchronized void clear(MinecraftServer server) {
        RouteStatusLiveness.clear();
        send(List.copyOf(server.getPlayerList().getPlayers()), List.of());
    }

    private static void send(List<ServerPlayer> recipients, List<RouteStatus.PlayerRoute> routes) {
        for (byte[] snapshot : RouteStatus.encodePackets(routes)) {
            for (ServerPlayer player : recipients) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SnapshotPayload(snapshot));
        }
    }
    private record SnapshotPayload(byte[] bytes) {
        private SnapshotPayload { bytes = bytes.clone(); }
    }
    private record HeartbeatPayload() {}

}
