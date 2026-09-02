package com.tailscale.mclink;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.ChatFormatting;

import java.util.List;
import java.util.UUID;

public final class ShareScreen extends Screen {
    private final Screen parent;
    private String invite;
    private boolean starting;
    private Component status;
    private boolean showInvite = false;
    private Button copyButton;
    private int copyFeedbackTicks = 0;

    public ShareScreen(Screen parent) {
        super(Component.translatable("mclink.share"));
        this.parent = parent;
        this.invite = McLinkClient.state().getInvite();
        if (McLinkClient.state().isHosting()) {
            this.status = Component.translatable("mclink.sharing");
        } else {
            this.status = Component.translatable("mclink.ready");
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (copyFeedbackTicks > 0) {
            copyFeedbackTicks--;
            if (copyFeedbackTicks == 0 && copyButton != null) {
                copyButton.setMessage(Component.translatable("mclink.copy"));
            }
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        boolean hosting = McLinkClient.state().isHosting();

        if (hosting) {
            int buttonY = height / 2 + 48;
            copyButton = addRenderableWidget(Button.builder(Component.translatable("mclink.copy"), b -> {
                if (invite != null && minecraft != null) {
                    minecraft.keyboardHandler.setClipboard(invite);
                    copyFeedbackTicks = 40;
                    b.setMessage(Component.translatable("mclink.copied"));
                }
            }).bounds(width / 2 - 102, buttonY, 100, 20).build());
            copyButton.active = invite != null;

            addRenderableWidget(Button.builder(Component.translatable("mclink.stop"), b -> {
                stopSharing();
            }).bounds(width / 2 + 2, buttonY, 100, 20).build());

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                onClose();
            }).bounds(width / 2 - 102, buttonY + 24, 204, 20).build());

            if (invite != null) {
                String inviteDisplay = showInvite ? shorten(invite) : "mcl1_\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
                int textW = this.font.width(inviteDisplay);
                int btnW = 34;
                int startX = (width - (textW + 6 + btnW)) / 2;
                int inviteY = height / 2 - 38;

                addRenderableWidget(Button.builder(
                        showInvite ? Component.translatable("mclink.hide") : Component.translatable("mclink.show"),
                        b -> {
                            showInvite = !showInvite;
                            rebuildWidgets();
                        }
                ).bounds(startX + textW + 6, inviteY - 4, btnW, 16).build());
            }

            if (invite == null && !starting) {
                starting = true;
                McLinkClient.state().share(minecraft).whenComplete((value, error) -> {
                    if (minecraft != null) {
                        minecraft.execute(() -> {
                            starting = false;
                            if (error != null) {
                                status = Component.literal("Could not share: " + rootMessage(error));
                            } else {
                                invite = value;
                                status = Component.translatable("mclink.sharing");
                                rebuildWidgets();
                            }
                        });
                    }
                });
            }
        } else {
            int buttonY = height / 2 + 20;
            Button startBtn = Button.builder(Component.translatable("mclink.start"), b -> {
                startSharing();
            }).bounds(width / 2 - 102, buttonY, 100, 20).build();
            startBtn.active = !starting;
            addRenderableWidget(startBtn);

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                onClose();
            }).bounds(width / 2 + 2, buttonY, 100, 20).build());
        }
    }

    private void startSharing() {
        if (starting) return;
        starting = true;
        status = Component.translatable("mclink.starting");
        rebuildWidgets();

        McLinkClient.state().share(minecraft).whenComplete((value, error) -> {
            if (minecraft != null) {
                minecraft.execute(() -> {
                    starting = false;
                    if (error != null) {
                        status = Component.literal("Could not share: " + rootMessage(error));
                        rebuildWidgets();
                    } else {
                        invite = value;
                        status = Component.translatable("mclink.sharing");
                        rebuildWidgets();
                    }
                });
            }
        });
    }

    private void stopSharing() {
        if (minecraft != null && minecraft.getSingleplayerServer() != null) {
            UUID hostId = minecraft.player != null ? minecraft.player.getUUID() : null;
            PlayerList playerList = minecraft.getSingleplayerServer().getPlayerList();
            Component disconnectReason = Component.translatable("mclink.stopped_message");
            for (ServerPlayer player : playerList.getPlayers()) {
                if (hostId == null || !player.getUUID().equals(hostId)) {
                    player.connection.disconnect(disconnectReason);
                }
            }
        }

        // Java 17 compatible: use plain Thread instead of Thread.ofVirtual()
        new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {}
            McLinkClient.state().stop();
            if (minecraft != null) {
                minecraft.execute(() -> {
                    invite = null;
                    status = Component.translatable("mclink.stopped_status");
                    rebuildWidgets();
                });
            }
        }).start();
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = width / 2;
        boolean hosting = McLinkClient.state().isHosting();

        if (hosting) {
            int currentY = height / 2 - 70;

            guiGraphics.drawCenteredString(this.font, title, centerX, currentY, 0xFFFFFFFF);
            guiGraphics.drawCenteredString(this.font, status, centerX, currentY + 16, 0xFFDDDDDD);

            if (invite != null) {
                String inviteDisplay = showInvite ? shorten(invite) : "mcl1_\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
                int textW = this.font.width(inviteDisplay);
                int startX = (width - (textW + 6 + 34)) / 2;
                int inviteY = currentY + 32;
                guiGraphics.drawString(this.font, inviteDisplay, startX, inviteY, 0xFFAAAAAA);
            }

            ScreenState state = McLinkClient.state();
            ScreenState.TransportMode mode = state.getTransportMode();

            List<ServerPlayer> guests = getGuestPlayers();
            if (guests.isEmpty()) {
                guiGraphics.drawCenteredString(this.font, Component.translatable("mclink.no_players"), centerX, currentY + 54, 0xFF888888);
            } else {
                Component countText = Component.translatable("mclink.connected_players", guests.size());
                guiGraphics.drawCenteredString(this.font, countText, centerX, currentY + 52, 0xFFFFFFFF);
                int playerY = currentY + 68;
                for (ServerPlayer player : guests) {
                    int latency = player.latency;
                    ChatFormatting pingFormat = latency < 80 ? ChatFormatting.GREEN : (latency < 160 ? ChatFormatting.YELLOW : ChatFormatting.RED);

                    String ip = player.getIpAddress();
                    boolean isTailcatProxy = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);

                    Component modeBadge;
                    ChatFormatting modeFormat;
                    if (!isTailcatProxy) {
                        modeBadge = Component.literal("[ ").append(Component.translatable("mclink.local_lan")).append(" ]");
                        modeFormat = ChatFormatting.AQUA;
                    } else if (mode == ScreenState.TransportMode.DIRECT) {
                        modeBadge = Component.literal("[ ").append(Component.translatable("mclink.direct")).append(" ]");
                        modeFormat = ChatFormatting.AQUA;
                    } else {
                        modeBadge = Component.literal("[ ").append(Component.translatable("mclink.relay")).append(" ]");
                        modeFormat = ChatFormatting.GOLD;
                    }

                    MutableComponent line = Component.literal("\u2022 " + player.getScoreboardName() + "   ")
                            .withStyle(ChatFormatting.WHITE)
                            .append(Component.literal(latency + "ms   ").withStyle(pingFormat))
                            .append(modeBadge.copy().withStyle(modeFormat));

                    guiGraphics.drawCenteredString(this.font, line, centerX, playerY, 0xFFFFFFFF);
                    playerY += 14;
                }
            }
        } else {
            int currentY = height / 2 - 30;
            guiGraphics.drawCenteredString(this.font, title, centerX, currentY, 0xFFFFFFFF);
            guiGraphics.drawCenteredString(this.font, status, centerX, currentY + 20, 0xFFDDDDDD);
        }
    }

    private List<ServerPlayer> getGuestPlayers() {
        if (minecraft == null || minecraft.getSingleplayerServer() == null) return List.of();
        UUID hostId = minecraft.player != null ? minecraft.player.getUUID() : null;
        return minecraft.getSingleplayerServer().getPlayerList().getPlayers().stream()
                .filter(p -> hostId == null || !p.getUUID().equals(hostId))
                .toList();
    }

    private static String shorten(String value) {
        return value.length() <= 48 ? value : value.substring(0, 22) + "\u2026" + value.substring(value.length() - 22);
    }

    static String rootMessage(Throwable error) {
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
