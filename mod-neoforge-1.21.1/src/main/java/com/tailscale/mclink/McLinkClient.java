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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

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
        if (screen instanceof PauseScreen && client.hasSingleplayerServer()) {
            addShareButton(client, screen, event);
        } else if (screen instanceof JoinMultiplayerScreen) {
            addConnectButton(client, screen, screen.width, screen.height, event);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        state.tick(client);
        String error = state.takeError();
        if (error != null) {
            SystemToast.add(
                    client.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("Remote LAN connection failed"),
                    Component.literal(error)
            );
        }
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        state.close();
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

        Button shareBtn = Button.builder(Component.translatable("mclink.share"), b -> client.setScreen(new ShareScreen(screen)))
                .bounds(screen.width / 2 - 102, newY, 204, 20)
                .build();
        event.addListener(shareBtn);
    }

    private static void addConnectButton(Minecraft client, Screen screen, int width, int height, ScreenEvent.Init.Post event) {
        if (findButton(event, "mclink.join") != null) return;
        Button direct = findButton(event, "selectServer.direct");
        if (direct == null) return;

        int row3Y = 0;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button b) {
                if (b.getY() > row3Y) {
                    row3Y = b.getY();
                }
                b.setY(b.getY() - 24);
            }
        }

        for (GuiEventListener child : screen.children()) {
            if (child instanceof ServerSelectionList list) {
                list.updateSizeAndPosition(width, height - 120, 32);
                break;
            }
        }

        Button joinBtn = Button.builder(Component.translatable("mclink.join"), b -> client.setScreen(new JoinRemoteScreen(screen)))
                .bounds(width / 2 - 102, row3Y, 204, 20)
                .build();
        event.addListener(joinBtn);
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
