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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

// Client-only
public final class McLinkClient {
    private static ScreenState state = new ScreenState();

    public McLinkClient() {
        RouteStatusPublisher.initialize();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(McLinkEvents.class);
    }

    public static ScreenState state() {
        return state;
    }

    @Mod.EventBusSubscriber(modid = "mclink", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class McLinkEvents {

        @SubscribeEvent
        public static void onPlayerLoggingIn(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
            RouteStatusCache.beginSession(event.getConnection());
        }

        @SubscribeEvent
        public static void onPlayerLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            RouteStatusCache.endSession(event.getConnection());
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
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != Phase.END) return;
            Minecraft client = Minecraft.getInstance();
            RouteStatusPublisher.sendHeartbeat();
            state.tick(client);
            String error = state.takeError();
            if (error != null) {
                SystemToast.add(
                    client.getToasts(),
                    SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("mclink.connection_failed"),
                    Component.literal(error)
                );
            }
        }

        @SubscribeEvent
        public static void onClientStopping(net.minecraftforge.event.GameShuttingDownEvent event) {
            state.close();
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
        int x;
        int y;
        int width;
        if (share != null) {
            y = share.getY();
            width = 100;
            share.setX(screen.width / 2 - 102);
            share.setWidth(width);
            x = screen.width / 2 + 2;
        } else {
            Button anchor = findButton(event, "menu.options");
            y = anchor == null ? screen.height - 48 : anchor.getY() + anchor.getHeight() + 4;
            if (anchor != null) {
                for (GuiEventListener listener : event.getListenersList()) {
                    if (listener instanceof Button button && button != anchor && button.getY() > anchor.getY()) button.setY(button.getY() + 24);
                }
            }
            width = Math.min(204, Math.max(1, screen.width - 32));
            x = (screen.width - width) / 2;
        }
        event.addListener(Button.builder(Component.translatable("mclink.status"),
                        button -> client.setScreen(new ConnectionStatusScreen(screen)))
                .bounds(x, y, width, 20).build());
    }

    private static void addConnectButton(Minecraft client, Screen screen, int width, int height, ScreenEvent.Init.Post event) {
        Button connect = findButton(event, "mclink.join");
        Button direct = findButton(event, "selectServer.direct");
        if (direct == null) return;

        int row1Y = height - 76;
        int row2Y = height - 52;
        int connectY = height - 28;
        int connectWidth = Math.min(204, Math.max(1, width - 32));
        int connectX = (width - connectWidth) / 2;

        setButtonY(event, "selectServer.select", row1Y);
        setButtonY(event, "selectServer.direct", row1Y);
        setButtonY(event, "selectServer.add", row1Y);
        setButtonY(event, "selectServer.edit", row2Y);
        setButtonY(event, "selectServer.delete", row2Y);
        setButtonY(event, "selectServer.refresh", row2Y);
        setButtonY(event, "gui.back", row2Y);
        if (connect != null) {
            connect.setPosition(connectX, connectY);
            connect.setWidth(connectWidth);
        }

        for (GuiEventListener child : screen.children()) {
            if (child instanceof ServerSelectionList list) {
                list.updateSize(width, height - 120, 32, height - 88);
                break;
            }
        }

        if (connect == null) {
            Button joinBtn = Button.builder(Component.translatable("mclink.join"),
                    b -> client.setScreen(new JoinRemoteScreen(screen)))
                    .bounds(connectX, connectY, connectWidth, 20)
                    .build();
            event.addListener(joinBtn);
        }
    }

    private static void setButtonY(ScreenEvent.Init.Post event, String translationKey, int y) {
        Button button = findButton(event, translationKey);
        if (button != null) button.setY(y);
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
