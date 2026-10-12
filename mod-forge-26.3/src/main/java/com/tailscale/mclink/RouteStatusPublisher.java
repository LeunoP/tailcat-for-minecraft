package com.tailscale.mclink;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;

final class RouteStatusPublisher {
    private static final CustomPacketPayload.Type<SnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("mclink", "connection_status"));
    private static final CustomPacketPayload.Type<HeartbeatPayload> HEARTBEAT_TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("mclink", "connection_heartbeat"));
    private static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeByte(0);
                buffer.writeBytes(payload.bytes());
            },
            buffer -> {
                if (!buffer.isReadable() || buffer.readUnsignedByte() != 0)
                    throw new IllegalArgumentException("Unknown Tailcat status packet type");
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                return new SnapshotPayload(bytes);
            });
    private static final StreamCodec<RegistryFriendlyByteBuf, HeartbeatPayload> HEARTBEAT_CODEC = StreamCodec.unit(new HeartbeatPayload());
    private static final Channel<CustomPacketPayload> CHANNEL;

    static {
        CHANNEL = ChannelBuilder.named(Identifier.fromNamespaceAndPath("mclink", "tailcat_payloads"))
                .networkProtocolVersion(1)
                .optional()
                .payloadChannel()
                .play().clientbound().addMain(TYPE, CODEC,
                (payload, context) -> receive(context.getConnection(), payload.bytes()))
                .serverbound().add(HEARTBEAT_TYPE, HEARTBEAT_CODEC, (payload, context) -> {
                    if (context.getSender() != null) RouteStatusLiveness.heartbeat(context.getSender().getUUID());
                }).build();
    }

    private RouteStatusPublisher() {}
    static void initialize() { /* Trigger channel registration on client-only installations. */ }

    static void sendHeartbeat() {
        if (RouteStatusHeartbeatClock.shouldSend()
                && net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            CHANNEL.send(new HeartbeatPayload(), PacketDistributor.SERVER.noArg());
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
                        routeForAddress.applyAsInt(player.connection.getRemoteAddress()), Math.max(-1, player.connection.latency()), RouteStatusHostRegistry.isHost(server, player.getName().getString())))
                .toList();
        send(players, routes);
    }

    static synchronized void clear(MinecraftServer server) {
        RouteStatusLiveness.clear();
        send(List.copyOf(server.getPlayerList().getPlayers()), List.of());
    }

    private static void send(List<ServerPlayer> recipients, List<RouteStatus.PlayerRoute> routes) {
        for (byte[] snapshot : RouteStatus.encodePackets(routes)) {
            SnapshotPayload payload = new SnapshotPayload(snapshot);
            for (ServerPlayer player : recipients) CHANNEL.send(payload, PacketDistributor.PLAYER.with(player));
        }
    }

    private record SnapshotPayload(byte[] bytes) implements CustomPacketPayload {
        private SnapshotPayload { bytes = bytes.clone(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record HeartbeatPayload() implements CustomPacketPayload {
        @Override public Type<? extends CustomPacketPayload> type() { return HEARTBEAT_TYPE; }
    }
}
