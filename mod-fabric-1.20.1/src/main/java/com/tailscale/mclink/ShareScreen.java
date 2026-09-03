package com.tailscale.mclink;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

public final class ShareScreen extends Screen {
    private final Screen parent;
    private String invite;
    private boolean starting;
    private Text status;
    private boolean showInvite = false;
    private ButtonWidget copyButton;
    private int copyFeedbackTicks = 0;

    public ShareScreen(Screen parent) {
        super(Text.translatable("mclink.share"));
        this.parent = parent;
        this.invite = McLinkClient.state().getInvite();
        if (McLinkClient.state().isHosting()) {
            this.status = Text.translatable("mclink.sharing");
        } else {
            this.status = Text.translatable("mclink.ready");
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (copyFeedbackTicks > 0) {
            copyFeedbackTicks--;
            if (copyFeedbackTicks == 0 && copyButton != null) {
                copyButton.setMessage(Text.translatable("mclink.copy"));
            }
        }
    }

    @Override
    protected void init() {
        clearChildren();
        boolean hosting = McLinkClient.state().isHosting();

        if (hosting) {
            int buttonY = height / 2 + 48;
            copyButton = addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.copy"), b -> {
                if (invite != null && client != null) {
                    client.keyboard.setClipboard(invite);
                    copyFeedbackTicks = 40;
                    b.setMessage(Text.translatable("mclink.copied"));
                }
            }).dimensions(width / 2 - 102, buttonY, 100, 20).build());
            copyButton.active = invite != null;

            addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.stop"), b -> {
                stopSharing();
            }).dimensions(width / 2 + 2, buttonY, 100, 20).build());

            // Back button: return to pause menu while keeping share active
            addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), b -> {
                close();
            }).dimensions(width / 2 - 102, buttonY + 24, 204, 20).build());

            if (invite != null) {
                String inviteDisplay = showInvite ? shorten(invite) : "mcl1_••••••••••••••••••••";
                int textW = textRenderer.getWidth(inviteDisplay);
                int btnW = 34;
                int totalW = textW + 6 + btnW;
                int startX = (width - totalW) / 2;
                int inviteY = height / 2 - 38;

                addDrawableChild(ButtonWidget.builder(
                        showInvite ? Text.translatable("mclink.hide") : Text.translatable("mclink.show"),
                        b -> {
                            showInvite = !showInvite;
                            clearAndInit();
                        }
                ).dimensions(startX + textW + 6, inviteY - 4, btnW, 16).build());
            }

            if (invite == null && !starting) {
                starting = true;
                McLinkClient.state().share(client).whenComplete((value, error) -> {
                    if (client != null) {
                        client.execute(() -> {
                            starting = false;
                            if (error != null) {
                                status = Text.literal("Could not share: " + rootMessage(error));
                            } else {
                                invite = value;
                                status = Text.translatable("mclink.sharing");
                                clearAndInit();
                            }
                        });
                    }
                });
            }
        } else {
            int buttonY = height / 2 + 20;
            ButtonWidget startBtn = ButtonWidget.builder(Text.translatable("mclink.start"), b -> {
                startSharing();
            }).dimensions(width / 2 - 102, buttonY, 100, 20).build();
            startBtn.active = !starting;
            addDrawableChild(startBtn);

            addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), b -> {
                close();
            }).dimensions(width / 2 + 2, buttonY, 100, 20).build());
        }
    }

    private void startSharing() {
        if (starting) return;
        starting = true;
        status = Text.translatable("mclink.starting");
        clearAndInit();

        McLinkClient.state().share(client).whenComplete((value, error) -> {
            if (client != null) {
                client.execute(() -> {
                    starting = false;
                    if (error != null) {
                        status = Text.literal("Could not share: " + rootMessage(error));
                        clearAndInit();
                    } else {
                        invite = value;
                        status = Text.translatable("mclink.sharing");
                        clearAndInit();
                    }
                });
            }
        });
    }

    private void stopSharing() {
        if (client != null) {
            IntegratedServer server = client.getServer();
            if (server != null) {
                UUID hostId = client.player != null ? client.player.getUuid() : null;
                Text disconnectReason = Text.translatable("mclink.stopped_message");
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (hostId == null || !player.getUuid().equals(hostId)) {
                        player.networkHandler.disconnect(disconnectReason);
                    }
                }
            }
        }

        Thread stopThread = new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {}
            McLinkClient.state().stop();
            if (client != null) {
                client.execute(() -> {
                    invite = null;
                    status = Text.translatable("mclink.stopped_status");
                    clearAndInit();
                });
            }
        }, "mclink-stop");
        stopThread.setDaemon(true);
        stopThread.start();
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);

        int centerX = width / 2;
        boolean hosting = McLinkClient.state().isHosting();

        if (hosting) {
            int currentY = height / 2 - 70;

            // Title
            context.drawCenteredTextWithShadow(this.textRenderer, title, centerX, currentY, 0xFFFFFFFF);

            // Status
            context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, currentY + 16, 0xFFDDDDDD);

            // Invite code (masked / unmasked)
            if (invite != null) {
                String inviteDisplay = showInvite ? shorten(invite) : "mcl1_••••••••••••••••••••";
                int textW = this.textRenderer.getWidth(inviteDisplay);
                int btnW = 34;
                int totalW = textW + 6 + btnW;
                int startX = (width - totalW) / 2;
                int inviteY = currentY + 32;

                context.drawTextWithShadow(this.textRenderer, inviteDisplay, startX, inviteY, 0xFFAAAAAA);
            }

            // Connected Players with Ping & Connection Mode
            ScreenState state = McLinkClient.state();
            ScreenState.TransportMode mode = state.getTransportMode();

            List<ServerPlayerEntity> guests = getGuestPlayers();
            if (guests.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("mclink.no_players"), centerX, currentY + 54, 0xFF888888);
            } else {
                Text countText = Text.translatable("mclink.connected_players", guests.size());
                context.drawCenteredTextWithShadow(this.textRenderer, countText, centerX, currentY + 52, 0xFFFFFFFF);
                int playerY = currentY + 68;
                for (ServerPlayerEntity player : guests) {
                    int latency = player.pingMilliseconds;
                    Formatting pingFormat = latency < 80 ? Formatting.GREEN : (latency < 160 ? Formatting.YELLOW : Formatting.RED);

                    MutableText line = Text.literal("• " + player.getName().getString() + "   ")
                            .formatted(Formatting.WHITE)
                            .append(Text.literal(latency + "ms").formatted(pingFormat));

                    context.drawCenteredTextWithShadow(this.textRenderer, line, centerX, playerY, 0xFFFFFFFF);
                    playerY += 14;
                }
            }
        } else {
            int currentY = height / 2 - 30;
            context.drawCenteredTextWithShadow(this.textRenderer, title, centerX, currentY, 0xFFFFFFFF);
            context.drawCenteredTextWithShadow(this.textRenderer, status, centerX, currentY + 20, 0xFFDDDDDD);
        }
    }

    private List<ServerPlayerEntity> getGuestPlayers() {
        if (client == null) return List.of();
        IntegratedServer server = client.getServer();
        if (server == null) return List.of();
        UUID hostId = client.player != null ? client.player.getUuid() : null;
        return server.getPlayerManager().getPlayerList().stream()
                .filter(p -> hostId == null || !p.getUuid().equals(hostId))
                .toList();
    }

    private static String shorten(String value) {
        return value.length() <= 48 ? value : value.substring(0, 22) + "…" + value.substring(value.length() - 22);
    }

    static String rootMessage(Throwable error) {
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
