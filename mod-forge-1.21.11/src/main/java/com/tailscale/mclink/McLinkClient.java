package com.tailscale.mclink;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;

// Client-only
public final class McLinkClient {
    private static final ScreenState state = new ScreenState();

    public McLinkClient() {
        RouteStatusPublisher.initialize();
        ScreenEvent.Init.Post.BUS.addListener(this::onScreenInit);
        TickEvent.ClientTickEvent.Post.BUS.addListener(this::onClientTick);
        net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(
                event -> RouteStatusCache.beginSession(event.getConnection()));
        net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(
                event -> RouteStatusCache.endSession(event.getConnection()));
        GameShuttingDownEvent.BUS.addListener(event -> state.close());
    }

    public static ScreenState state() {
        return state;
    }

    private void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        Minecraft client = Minecraft.getInstance();
        if (screen instanceof PauseScreen) {
            if (client.hasSingleplayerServer()) addShareButton(client, screen, event);
            if (state.hasActiveSession() || ConnectionStatusScreen.isAvailable(client)) addStatusButton(client, screen, event);
        } else if (screen instanceof JoinMultiplayerScreen) {
            addConnectButton(client, screen, screen.width, screen.height, event);
        }
    }

    private void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        state.tick(client);
        String error = state.takeError();
        if (error != null) {
            SystemToast.add(
                client.getToastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("mclink.connection_failed"),
                Component.literal(error)
            );
        }
    }

    private static void addShareButton(Minecraft client, Screen screen, ScreenEvent.Init.Post event) {
        if (findButton(event, "mclink.share") != null) return;
        Button anchor = findButton(event, "menu.options");
        if (anchor == null) return;

        int newY = anchor.getY() + anchor.getHeight() + 4;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button b && b.getY() > anchor.getY()) {
                b.setY(b.getY() + 24);
            }
        }

        Button shareBtn = Button.builder(Component.translatable("mclink.share"),
                b -> client.setScreen(new ShareScreen(screen)))
                .bounds(screen.width / 2 - 102, newY, 204, 20)
                .build();
        event.addListener(shareBtn);
    }

    private static void addStatusButton(Minecraft client, Screen screen, ScreenEvent.Init.Post event) {
        if (findButton(event, "mclink.status") != null) return;
        Button share = findButton(event, "mclink.share");
        int x, y, buttonWidth;
        if (share != null) {
            y = share.getY(); buttonWidth = 100;
            share.setX(screen.width / 2 - 102); share.setWidth(buttonWidth);
            x = screen.width / 2 + 2;
        } else {
            Button anchor = findButton(event, "menu.options");
            y = anchor == null ? screen.height - 48 : anchor.getY() + anchor.getHeight() + 4;
            if (anchor != null) for (GuiEventListener listener : event.getListenersList())
                if (listener instanceof Button b && b != anchor && b.getY() > anchor.getY()) b.setY(b.getY() + 24);
            buttonWidth = Math.min(204, Math.max(1, screen.width - 32));
            x = (screen.width - buttonWidth) / 2;
        }
        event.addListener(Button.builder(Component.translatable("mclink.status"),
                        b -> client.setScreen(new ConnectionStatusScreen(screen)))
                .bounds(x, y, buttonWidth, 20).build());
    }

    private static void addConnectButton(Minecraft client, Screen screen, int width, int height, ScreenEvent.Init.Post event) {
        Button direct = findButton(event, "selectServer.direct");
        if (direct == null) return;

        Button connect = findButton(event, "mclink.join");
        int row3Y = event.getListenersList().stream()
                .filter(listener -> listener != connect)
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .mapToInt(Button::getY)
                .max()
                .orElse(height - 28);
        int connectWidth = Math.min(204, Math.max(1, width - 32));
        int connectX = (width - connectWidth) / 2;

        // This callback runs once after vanilla lays out the screen. Reapply
        // the reserved row on every init, then place a reused Tailcat button
        // against the current dimensions rather than its previous coordinates.
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button b && b != connect) {
                b.setY(b.getY() - 24);
            }
        }
        if (connect != null) {
            connect.setPosition(connectX, row3Y);
            connect.setWidth(connectWidth);
        }

        for (GuiEventListener child : screen.children()) {
            if (child instanceof ServerSelectionList list) {
                list.updateSizeAndPosition(width, height - 120, 32);
                break;
            }
        }

        if (connect == null) {
            Button joinBtn = Button.builder(Component.translatable("mclink.join"),
                    b -> client.setScreen(new JoinRemoteScreen(screen)))
                    .bounds(connectX, row3Y, connectWidth, 20)
                    .build();
            event.addListener(joinBtn);
        }
    }

    private static Button findButton(ScreenEvent.Init.Post event, String translationKey) {
        String label = Component.translatable(translationKey).getString();
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button b && b.getMessage().getString().equals(label)) {
                return b;
            }
        }
        return null;
    }
}
