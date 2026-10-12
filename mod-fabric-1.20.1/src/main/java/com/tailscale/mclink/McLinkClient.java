package com.tailscale.mclink;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

public final class McLinkClient implements ClientModInitializer {
    private static ScreenState state;

    @Override public void onInitializeClient() {
        RouteStatusPublisher.registerHeartbeatReceiver();
        ClientPlayNetworking.registerGlobalReceiver(RouteStatusPublisher.CHANNEL,
                (client, handler, buffer, responseSender) -> {
                    if (buffer.isReadable() && buffer.readUnsignedByte() == 0) {
                        byte[] bytes = new byte[buffer.readableBytes()];
                        buffer.readBytes(bytes);
                        var connection = handler.getConnection();
                        client.execute(() -> RouteStatusPublisher.receive(connection, bytes));
                    }
                });
        ClientPlayConnectionEvents.INIT.register((handler, client) -> RouteStatusCache.beginSession(handler.getConnection()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RouteStatusCache.endSession(handler.getConnection()));
        state = new ScreenState();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof GameMenuScreen) {
                if (client.isIntegratedServerRunning()) addShareButton(client, screen);
                if (ConnectionStatusScreen.isAvailable(client)) addStatusButton(client, screen);
            } else if (screen instanceof MultiplayerScreen) {
                addConnectButton(client, screen, width, height);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RouteStatusPublisher.sendHeartbeat(client);
            state.tick(client);
            String error = state.takeError();
            if (error != null) SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.translatable("mclink.connection_failed"), Text.literal(error));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> state.close());
    }

    public static ScreenState state() { return state; }

    private static void addShareButton(MinecraftClient client, net.minecraft.client.gui.screen.Screen screen) {
        if (findButton(screen, "mclink.share") != null) return;
        ButtonWidget anchor = findButton(screen, "menu.options");
        if (anchor == null) return;
        int newY = anchor.getY() + anchor.getHeight() + 4;
        Screens.getButtons(screen).stream().filter(button -> button.getY() > anchor.getY())
                .forEach(button -> button.setY(button.getY() + 24));
        Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("mclink.share"),
                        button -> client.setScreen(new ShareScreen(screen)))
                .dimensions(screen.width / 2 - 102, newY, 204, 20).build());
    }

    private static void addStatusButton(MinecraftClient client, net.minecraft.client.gui.screen.Screen screen) {
        if (findButton(screen, "mclink.status") != null) return;
        ButtonWidget share = findButton(screen, "mclink.share");
        int x, y, buttonWidth;
        if (share != null) {
            y = share.getY(); buttonWidth = 100;
            share.setPosition(screen.width / 2 - 102, y); share.setWidth(buttonWidth);
            x = screen.width / 2 + 2;
        } else {
            ButtonWidget anchor = findButton(screen, "menu.options");
            y = anchor == null ? screen.height - 48 : anchor.getY() + anchor.getHeight() + 4;
            if (anchor != null) Screens.getButtons(screen).stream()
                    .filter(button -> button != anchor && button.getY() > anchor.getY())
                    .forEach(button -> button.setY(button.getY() + 24));
            buttonWidth = Math.min(204, Math.max(1, screen.width - 32));
            x = (screen.width - buttonWidth) / 2;
        }
        Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("mclink.status"),
                        button -> client.setScreen(new ConnectionStatusScreen(screen)))
                .dimensions(x, y, buttonWidth, 20).build());
    }

    private static void addConnectButton(MinecraftClient client, net.minecraft.client.gui.screen.Screen screen, int width, int height) {
        ButtonWidget connect = findButton(screen, "mclink.join");
        ButtonWidget direct = findButton(screen, "selectServer.direct");
        if (direct == null) return;
        int row3Y = Screens.getButtons(screen).stream()
                .filter(button -> button != connect)
                .mapToInt(net.minecraft.client.gui.widget.ClickableWidget::getY)
                .max()
                .orElse(height - 28);
        int connectWidth = Math.min(204, Math.max(1, width - 32));
        int connectX = (width - connectWidth) / 2;
        Screens.getButtons(screen).stream()
                .filter(button -> button != connect)
                .forEach(button -> button.setY(button.getY() - 24));
        if (connect != null) {
            connect.setPosition(connectX, row3Y);
            connect.setWidth(connectWidth);
        }
        for (var child : screen.children()) {
            if (child instanceof MultiplayerServerListWidget list) {
                list.updateSize(width, height, 32, height - 88);
                break;
            }
        }
        if (connect == null) {
            Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("mclink.join"),
                            button -> client.setScreen(new JoinRemoteScreen(screen)))
                    .dimensions(connectX, row3Y, connectWidth, 20).build());
        }
    }

    private static ButtonWidget findButton(net.minecraft.client.gui.screen.Screen screen, String translationKey) {
        String label = Text.translatable(translationKey).getString();
        return Screens.getButtons(screen).stream()
                .filter(ButtonWidget.class::isInstance)
                .map(ButtonWidget.class::cast)
                .filter(button -> button.getMessage().getString().equals(label))
                .findFirst().orElse(null);
    }
}
