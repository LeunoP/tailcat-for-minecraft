package com.tailscale.mclink;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

final class RouteStatusPublisher {
    private static final int MAX_PAYLOAD_SIZE = RouteStatus.MAX_PAYLOAD_BYTES;
    static final CustomPayload.Id<SnapshotPayload> ID = new CustomPayload.Id<>(
            Identifier.of("mclink", "connection_status"));
    static final PacketCodec<RegistryByteBuf, SnapshotPayload> CODEC = PacketCodec.of(
            (payload, buffer) -> {
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
    private static boolean registered;

    private RouteStatusPublisher() {}

    static synchronized void registerPayload() {
        if (registered) return;
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
        registered = true;
    }

    static void receive(Object connection, SnapshotPayload payload) {
        try {
            RouteStatusCache.accept(connection, payload.bytes);
        } catch (IOException | RuntimeException invalid) {
            // Invalid messages do not renew the last validated snapshot.
        }
    }

    static synchronized void publish(MinecraftServer server, ToIntFunction<SocketAddress> routeForAddress) {
        List<RouteStatus.PlayerRoute> routes = server.getPlayerManager().getPlayerList().stream()
                .map(player -> new RouteStatus.PlayerRoute(player.getName().getString(),
                        routeForAddress.applyAsInt(player.networkHandler.getConnectionAddress()), Math.max(-1, player.networkHandler.getLatency())))
                .toList();
        send(server.getPlayerManager().getPlayerList(), routes);
    }

    static synchronized void clear(MinecraftServer server) {
        send(server.getPlayerManager().getPlayerList(), List.of());
    }

    private static void send(List<ServerPlayerEntity> recipients, List<RouteStatus.PlayerRoute> routes) {
        for (byte[] snapshot : RouteStatus.encodePackets(routes)) {
            SnapshotPayload payload = new SnapshotPayload(snapshot);
            for (ServerPlayerEntity player : recipients) ServerPlayNetworking.send(player, payload);
        }
    }

    record SnapshotPayload(byte[] bytes) implements CustomPayload {
        SnapshotPayload { bytes = bytes.clone(); }
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}
