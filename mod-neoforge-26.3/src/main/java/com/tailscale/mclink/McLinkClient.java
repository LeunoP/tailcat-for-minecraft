package com.tailscale.mclink;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Client-only event subscriber
@EventBusSubscriber(modid = "mclink", value = Dist.CLIENT)
public final class McLinkClient {
    private static ScreenState state = new ScreenState();

    public McLinkClient() {
    }

    public static ScreenState state() {
        return state;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        Minecraft client = Minecraft.getInstance();
        if (screen instanceof PauseScreen) {
            if (client.hasSingleplayerServer()) addShareButton(client, screen, event);
            if (ConnectionStatusScreen.isAvailable(client)) addStatusButton(client, screen, event);
        } else if (screen instanceof JoinMultiplayerScreen) {
            addConnectButton(client, screen, screen.width, screen.height, event);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggingIn(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        RouteStatusCache.beginSession(event.getConnection());
    }

    @SubscribeEvent
    public static void onPlayerLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        RouteStatusCache.endSession(event.getConnection());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        RouteStatusPublisher.sendHeartbeat();
        state.tick(client);
        String error = state.takeError();
        if (error != null) {
            SystemToast.add(
                    client.gui.toastManager(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("mclink.connection_failed"),
                    Component.literal(error)
            );
        }
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        state.close();
    }

    private static void addShareButton(Minecraft client, Screen screen, ScreenEvent.Init.Post event) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof SpriteIconButton sib && Component.translatable("mclink.share").equals(sib.getMessage())) {
                return;
            }
        }

        Identifier spriteId = Identifier.fromNamespaceAndPath("mclink", "tailcat");
        SpriteIconButton tailcatButton = SpriteIconButton.builder(
                        Component.translatable("mclink.share"),
                        button -> client.gui.setScreen(new ShareScreen(screen)),
                        true
                )
                .size(20, 20)
                .sprite(spriteId, 16, 16)
                .tooltip(Component.translatable("mclink.share"))
                .build();

        List<AbstractWidget> iconButtons = new ArrayList<>();
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w.getWidth() == 20 && w.getHeight() == 20) {
                iconButtons.add(w);
            }
        }
        iconButtons.sort(Comparator.comparingInt(AbstractWidget::getX));

        if (!iconButtons.isEmpty()) {
            int y = iconButtons.get(0).getY();
            int count = iconButtons.size() + 1;
            int totalWidth = count * 20 + (count - 1) * 4;
            int startX = (screen.width - totalWidth) / 2;

            for (int i = 0; i < iconButtons.size(); i++) {
                iconButtons.get(i).setX(startX + i * 24);
            }
            tailcatButton.setX(startX + iconButtons.size() * 24);
            tailcatButton.setY(y);
        } else {
            Button lan = findButton(event, "menu.shareToLan");
            if (lan == null) lan = findButton(event, "menu.options");
            if (lan != null) {
                tailcatButton.setX(lan.getX() + lan.getWidth() - 20);
                tailcatButton.setY(lan.getY() - 24);
            }
        }

        event.addListener(tailcatButton);
    }

    private static void addStatusButton(Minecraft client, Screen screen, ScreenEvent.Init.Post event) {
        if (findButton(event, "mclink.status") != null) return;
        Button anchor = findButton(event, "menu.options");
        int y = anchor == null ? screen.height - 48 : anchor.getY() + anchor.getHeight() + 4;
        if (anchor != null) {
            for (GuiEventListener listener : event.getListenersList()) {
                if (listener instanceof Button button && button != anchor && button.getY() > anchor.getY()) button.setY(button.getY() + 24);
            }
        }
        int buttonWidth = Math.min(204, Math.max(1, screen.width - 32));
        event.addListener(Button.builder(Component.translatable("mclink.status"),
                        button -> client.gui.setScreen(new ConnectionStatusScreen(screen)))
                .bounds((screen.width - buttonWidth) / 2, y, buttonWidth, 20).build());
    }

    private static void addConnectButton(Minecraft client, Screen screen, int width, int height, ScreenEvent.Init.Post event) {
        Button connect = findButton(event, "mclink.join");

        HeaderAndFooterLayout layout = null;
        try {
            for (Field field : JoinMultiplayerScreen.class.getDeclaredFields()) {
                if (HeaderAndFooterLayout.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    layout = (HeaderAndFooterLayout) field.get(screen);
                    break;
                }
            }
        } catch (Exception ignored) {}

        if (layout != null) {
            LinearLayout[] footerHolder = new LinearLayout[1];
            layout.visitChildren(element -> {
                if (element instanceof LinearLayout ll) {
                    footerHolder[0] = ll;
                }
        });

        if (footerHolder[0] != null) {
            int connectWidth = Math.min(308, Math.max(1, width - 32));
            if (connect == null) {
                connect = Button.builder(Component.translatable("mclink.join"),
                                button -> client.gui.setScreen(new JoinRemoteScreen(screen)))
                        .width(connectWidth).build();
                footerHolder[0].addChild(connect);
                event.addListener(connect);
            } else {
                connect.setWidth(connectWidth);
            }
            layout.setFooterHeight(88);
            layout.arrangeElements();
                for (var child : screen.children()) {
                    if (child instanceof ServerSelectionList list) {
                        list.updateSize(width, layout);
                        break;
                    }
                }
                return;
            }
        }

        Button direct = findButton(event, "selectServer.direct");
        if (direct != null) {
            Button currentConnect = connect;
            int row3Y = event.getListenersList().stream()
                    .filter(listener -> listener != currentConnect)
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .mapToInt(Button::getY)
                    .max()
                    .orElse(height - 28);
            int connectWidth = Math.min(204, Math.max(1, width - 32));
            int connectX = (width - connectWidth) / 2;
            for (GuiEventListener listener : event.getListenersList()) {
                if (listener instanceof Button b && b != connect) {
                    b.setY(b.getY() - 24);
                }
            }
            if (connect == null) {
                Button joinBtn = Button.builder(Component.translatable("mclink.join"),
                        b -> client.gui.setScreen(new JoinRemoteScreen(screen)))
                        .bounds(connectX, row3Y, connectWidth, 20)
                        .build();
                event.addListener(joinBtn);
            } else {
                connect.setPosition(connectX, row3Y);
                connect.setWidth(connectWidth);
            }
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
