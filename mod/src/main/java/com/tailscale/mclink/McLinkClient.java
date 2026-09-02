package com.tailscale.mclink;

import com.tailscale.mclink.mixin.JoinMultiplayerScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Comparator;
import java.util.List;

public final class McLinkClient implements ClientModInitializer {
    private static ScreenState state;

    @Override public void onInitializeClient() {
        state = new ScreenState();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen && client.hasSingleplayerServer()) {
                addShareButton(client, screen);
            } else if (screen instanceof JoinMultiplayerScreen) {
                addConnectButton(client, screen, width, height);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            state.tick(client);
            String error = state.takeError();
            if (error != null) {
                SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("Remote LAN connection failed"), Component.literal(error));
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> state.close());
    }

    public static ScreenState state() { return state; }

    private static void addShareButton(Minecraft client, Screen screen) {
        var widgets = Screens.getWidgets(screen);
        if (widgets.stream().anyMatch(w -> w instanceof SpriteIconButton sib && Component.translatable("mclink.share").equals(sib.getMessage()))) {
            return;
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

        // Find existing 20x20 icon buttons in row 3 (Bugs, Feedback, Friends, Reporting)
        List<AbstractWidget> iconButtons = widgets.stream()
                .filter(w -> w.getWidth() == 20 && w.getHeight() == 20)
                .sorted(Comparator.comparingInt(AbstractWidget::getX))
                .toList();

        if (!iconButtons.isEmpty()) {
            int y = iconButtons.get(0).getY();
            int count = iconButtons.size() + 1; // 4 + 1 = 5 buttons
            int totalWidth = count * 20 + (count - 1) * 4; // 116px
            int startX = (screen.width - totalWidth) / 2;

            for (int i = 0; i < iconButtons.size(); i++) {
                iconButtons.get(i).setX(startX + i * 24);
            }
            tailcatButton.setX(startX + iconButtons.size() * 24);
            tailcatButton.setY(y);
        } else {
            Button lan = findButton(screen, "menu.shareToLan");
            if (lan == null) lan = findButton(screen, "menu.multiplayerOptions.button");
            if (lan != null) {
                tailcatButton.setX(lan.getX() + lan.getWidth() - 20);
                tailcatButton.setY(lan.getY() - 24);
            }
        }

        widgets.add(tailcatButton);
    }

    private static void addConnectButton(Minecraft client, Screen screen, int width, int height) {
        if (findButton(screen, "mclink.join") != null) return;
        if (!(screen instanceof JoinMultiplayerScreen multiplayerScreen)) return;

        HeaderAndFooterLayout layout = ((JoinMultiplayerScreenAccessor) multiplayerScreen).mclink$getLayout();
        if (layout == null) return;

        LinearLayout[] footerHolder = new LinearLayout[1];
        layout.visitChildren(element -> {
            if (element instanceof LinearLayout ll) {
                footerHolder[0] = ll;
            }
        });

        if (footerHolder[0] != null) {
            Button tailcatButton = Button.builder(Component.translatable("mclink.join"),
                            button -> client.gui.setScreen(new JoinRemoteScreen(screen)))
                    .width(308).build();
            footerHolder[0].addChild(tailcatButton);
            Screens.getWidgets(screen).add(tailcatButton);
            layout.setFooterHeight(88);
            layout.arrangeElements();
            for (var child : screen.children()) {
                if (child instanceof ServerSelectionList list) {
                    list.updateSize(width, layout);
                    break;
                }
            }
        }
    }

    private static Button findButton(Screen screen, String translationKey) {
        String label = Component.translatable(translationKey).getString();
        return Screens.getWidgets(screen).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label))
                .findFirst().orElse(null);
    }
}

