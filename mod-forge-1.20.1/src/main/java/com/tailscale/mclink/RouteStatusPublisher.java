package com.tailscale.mclink;

import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.network.simple.SimpleChannel;

final class RouteStatusPublisher {
    private static final String PROTOCOL = "1";
    private static final ResourceLocation HEARTBEAT_ID = new ResourceLocation("mclink", "connection_heartbeat");
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("mclink", "connection_status"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));
    private static final EventNetworkChannel HEARTBEAT_CHANNEL = NetworkRegistry.newEventChannel(
            HEARTBEAT_ID, () -> PROTOCOL,
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
        HEARTBEAT_CHANNEL.addListener((NetworkEvent.ServerCustomPayloadEvent event) -> {
            if (event.getPayload() != null && event.getPayload().isReadable()) return;
            NetworkEvent.Context context = event.getSource().get();
            if (context.getSender() != null) RouteStatusLiveness.heartbeat(context.getSender().getUUID());
            context.setPacketHandled(true);
        });
    }

    private RouteStatusPublisher() {}
    static void initialize() { /* Trigger channel registration on client-only installations. */ }

    static void sendHeartbeat() {
        if (RouteStatusHeartbeatClock.shouldSend()
                && net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            net.minecraft.client.Minecraft.getInstance().getConnection().getConnection().send(
                    new ServerboundCustomPayloadPacket(HEARTBEAT_ID,
                            new FriendlyByteBuf(Unpooled.EMPTY_BUFFER.copy())));
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

}
