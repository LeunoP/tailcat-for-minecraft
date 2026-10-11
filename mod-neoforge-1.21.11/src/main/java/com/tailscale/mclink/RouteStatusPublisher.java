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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

public final class RouteStatusPublisher {
    private static final int MAX_PAYLOAD_SIZE = RouteStatus.MAX_PAYLOAD_BYTES;
    private static final CustomPacketPayload.Type<SnapshotPayload> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("mclink", "connection_status"));
    private static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeByte(0);
                buffer.writeBytes(payload.bytes);
            },
            buffer -> {
                if (!buffer.isReadable() || buffer.readUnsignedByte() != 0)
                    throw new IllegalArgumentException("Unknown Tailcat status packet type");
                int size = buffer.readableBytes();
                if (size > MAX_PAYLOAD_SIZE) throw new IllegalArgumentException("Tailcat status packet is too large");
                byte[] bytes = new byte[size];
                buffer.readBytes(bytes);
                return new SnapshotPayload(bytes);
            });

    private RouteStatusPublisher() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().executesOn(HandlerThread.MAIN)
                .playToClient(TYPE, CODEC, (payload, context) -> receive(context.connection(), payload));
    }

    private static void receive(Object connection, SnapshotPayload payload) {
        try { RouteStatusCache.accept(connection, payload.bytes); }
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
            SnapshotPayload payload = new SnapshotPayload(snapshot);
            for (ServerPlayer player : recipients) PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private record SnapshotPayload(byte[] bytes) implements CustomPacketPayload {
        private SnapshotPayload { bytes = bytes.clone(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
