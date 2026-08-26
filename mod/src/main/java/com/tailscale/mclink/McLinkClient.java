package com.tailscale.mclink;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
        state = new ScreenState();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof GameMenuScreen && client.isIntegratedServerRunning()) {
                addShareButton(client, screen);
            } else if (screen instanceof MultiplayerScreen) {
                addConnectButton(client, screen, width, height);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            state.tick(client);
            String error = state.takeError();
            if (error != null) SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.literal("Remote LAN connection failed"), Text.literal(error));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> state.close());
    }

    public static ScreenState state() { return state; }

    private static void addShareButton(MinecraftClient client, net.minecraft.client.gui.screen.Screen screen) {
        ButtonWidget lan = findButton(screen, "menu.shareToLan");
        if (lan == null) return;
        int newY = lan.getY() + lan.getHeight() + 4;
        Screens.getButtons(screen).stream().filter(button -> button.getY() > lan.getY())
                .forEach(button -> button.setY(button.getY() + 24));
        Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("mclink.share"),
                        button -> client.setScreen(new ShareScreen(screen)))
                .dimensions(lan.getX(), newY, lan.getWidth(), lan.getHeight()).build());
    }

    private static void addConnectButton(MinecraftClient client, net.minecraft.client.gui.screen.Screen screen, int width, int height) {
        ButtonWidget direct = findButton(screen, "selectServer.direct");
        if (direct == null) return;
        int firstRowY = direct.getY();
        Screens.getButtons(screen).stream().filter(button -> button.getY() == firstRowY)
                .forEach(button -> button.setY(button.getY() - 24));
        for (var child : screen.children()) {
            if (child instanceof MultiplayerServerListWidget list) {
                list.setDimensionsAndPosition(width, height - 120, 0, 32);
                break;
            }
        }
        Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("mclink.join"),
                        button -> client.setScreen(new JoinRemoteScreen(screen)))
                .dimensions(width / 2 - 102, firstRowY, 204, 20).build());
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
