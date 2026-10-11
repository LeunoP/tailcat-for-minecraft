package com.tailscale.mclink;

import java.math.BigInteger;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;


public final class ShareScreen extends Screen {
    private final Screen parent;
    private String invite;
    private boolean starting;
    private boolean hostingLayout;
    private Component status;
    private boolean relayOnly;
    private String maxPlayersText = "8";
    private EditBox playerCountInput;
    private Button startButton;
    private boolean showInvite = false;
    private Button copyButton;
    private int copyFeedbackTicks = 0;

    public ShareScreen(Screen parent) {
        super(Component.translatable("mclink.share"));
        this.parent = parent;
        var remembered = HostPreferences.SESSION.get();
        relayOnly = remembered.relayOnly();
        maxPlayersText = Integer.toString(remembered.maxPlayers());
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
        if (hostingLayout && !starting && !McLinkClient.state().isHosting()) {
            invite = null;
            status = Component.translatable(McLinkClient.state().phaseKey());
            rebuildWidgets();
        }
        if (starting) status = Component.translatable(McLinkClient.state().phaseKey());
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
        startButton = null;
        boolean hosting = McLinkClient.state().isHosting() && !starting;
        hostingLayout = hosting;

        if (hosting) {
            int buttonY = height / 2 + 48;
            addRenderableWidget(Button.builder(Component.translatable("mclink.connection_info"), b -> {
                minecraft.gui.setScreen(new ConnectionStatusScreen(this));
            }).bounds(width / 2 - 102, height / 2 + 16, 204, 20).build());
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

            // Back button: return to pause menu while keeping share active
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                onClose();
            }).bounds(width / 2 - 102, buttonY + 24, 204, 20).build());

            if (invite != null) {
                String inviteDisplay = showInvite ? shorten(invite) : "mcl1_••••••••••••••••••••";
                int textW = font.width(inviteDisplay);
                int btnW = 34;
                int totalW = textW + 6 + btnW;
                int startX = (width - totalW) / 2;
                int inviteY = height / 2 - 54;

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
                                status = Component.translatable("mclink.error_share", Component.translatable(FailureGuide.key(error)));
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
            int buttonY = height / 2 + 40;
            playerCountInput = new EditBox(font, width / 2 - 102, height / 2 + 12, 204, 20,
                    Component.translatable("mclink.next_share_capacity")) {
                @Override
                public void setValue(String value) {
                    if (value.matches("[0-9]*")) super.setValue(value);
                }

                @Override
                public void insertText(String text) {
                    if (text.matches("[0-9]*")) super.insertText(text);
                }

                @Override
                public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent event) {
                    return super.preeditUpdated(event != null && event.fullText().matches("[0-9]*") ? event : null);
                }
            };
            playerCountInput.setMaxLength(10);
            playerCountInput.setHint(Component.translatable("mclink.max_players_hint"));
            playerCountInput.setValue(maxPlayersText);
            playerCountInput.setEditable(!starting);
            playerCountInput.setResponder(value -> {
                maxPlayersText = value;
                if (startButton != null) {
                    startButton.active = !starting && validPlayerCount();
                }
            });
            addRenderableWidget(playerCountInput);

            Button relayButton = addRenderableWidget(Button.builder(relayOnlyLabel(), b -> {
                relayOnly = !relayOnly;
                rememberHostSettings();
                rebuildWidgets();
            }).bounds(width / 2 - 102, height / 2 - 24, 204, 20).build());
            relayButton.active = !starting;

            startButton = addRenderableWidget(Button.builder(Component.translatable("mclink.start"), b -> {
                startSharing();
            }).bounds(width / 2 - 102, buttonY, 100, 20).build());
            startButton.active = !starting && validPlayerCount();

            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> {
                onClose();
            }).bounds(width / 2 + 2, buttonY, 100, 20).build());
            setInitialFocus(playerCountInput);
        }
    }

    private void startSharing() {
        if (starting || !validPlayerCount()) return;
        BigInteger maxPlayers = new BigInteger(maxPlayersText);
        rememberHostSettings();
        starting = true;
        status = Component.translatable("mclink.starting");
        rebuildWidgets();

        McLinkClient.state().share(minecraft, relayOnly, maxPlayers).whenComplete((value, error) -> {
            if (minecraft != null) {
                minecraft.execute(() -> {
                    starting = false;
                    if (error != null) {
                        status = Component.translatable("mclink.error_share", Component.translatable(FailureGuide.key(error)));
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
        Runnable stopCurrentSession = McLinkClient.state().stopAction();
        // Disconnect connected remote players with notification message
        if (minecraft != null) {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                Component disconnectReason = Component.translatable("mclink.stopped_message");
                server.execute(() -> {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (McLinkClient.state().owns(player.connection.getRemoteAddress())) {
                        player.connection.disconnect(disconnectReason);
                    }
                }
                });
            }
        }

        // Allow 250ms for disconnect packets to flush over the wire before killing the helper
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {}
            if (minecraft != null) minecraft.execute(stopCurrentSession);
            if (minecraft != null) {
                minecraft.execute(() -> {
                    invite = null;
                    status = Component.translatable("mclink.stopped_status");
                    rebuildWidgets();
                });
            }
        });
    }

    @Override
    public void onClose() {
        rememberHostSettings();
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        int centerX = width / 2;
        boolean hosting = hostingLayout;

        if (hosting) {
            int currentY = height / 2 - 86;

            // Title: White (0xFFFFFFFF)
            extractor.centeredText(this.font, title, centerX, currentY, 0xFFFFFFFF);

            // Status: Light Gray (0xFFDDDDDD)
            renderStatus(extractor, currentY + 16, 1);
            renderNote(extractor, Component.translatable("mclink.current_capacity", currentMaxPlayers()), currentY + 50, 1);

            // Invite code: Gray (0xFFAAAAAA)
            if (invite != null) {
                String inviteDisplay = showInvite ? shorten(invite) : "mcl1_••••••••••••••••••••";
                int textW = this.font.width(inviteDisplay);
                int btnW = 34;
                int totalW = textW + 6 + btnW;
                int startX = (width - totalW) / 2;
                int inviteY = currentY + 32;

                extractor.text(this.font, Component.literal(inviteDisplay),
                        startX, inviteY, 0xFFAAAAAA);
            }

        } else {
            int currentY = height / 2 - 84;
            extractor.centeredText(this.font, title, centerX, currentY, 0xFFFFFFFF);
            renderStatus(extractor, currentY + 20, 2);
            renderNote(extractor, Component.translatable("mclink.current_capacity", currentMaxPlayers()), height / 2 - 38, 1);
            renderNote(extractor, Component.translatable("mclink.next_share_capacity"), height / 2 + 1, 1);
        }
        if (preservesExistingLan()) {
            renderNote(extractor, Component.translatable("mclink.existing_lan_kept"), height / 2 + (hosting ? 100 : 66), 2);
        }
    }


    private void renderStatus(GuiGraphicsExtractor extractor, int y, int rows) {
        renderNote(extractor, status, y, rows);
    }

    private void renderNote(GuiGraphicsExtractor extractor, Component message, int y, int rows) {
        var lines = font.split(message, Math.max(1, Math.min(300, width - 32)));
        int visible = Math.min(rows, lines.size());
        if (visible == 0) return;
        for (int i = 0; i < visible; i++) {
            extractor.centeredText(font, lines.get(i), width / 2, y + i * 10, 0xFFDDDDDD);
        }
    }

    private int currentMaxPlayers() {
        var server = minecraft == null ? null : minecraft.getSingleplayerServer();
        return server == null ? 0 : server.getPlayerList().getMaxPlayers();
    }

    private boolean preservesExistingLan() {
        var server = minecraft == null ? null : minecraft.getSingleplayerServer();
        if (server == null) return false;
        return McLinkClient.state().isHosting() ? McLinkClient.state().keepsExistingLan() : server.getPort() > 0;
    }

    private void rememberHostSettings() {
        var previous = HostPreferences.SESSION.get();
        int count = validPlayerCount() ? new BigInteger(maxPlayersText).intValue() : previous.maxPlayers();
        HostPreferences.SESSION.remember(relayOnly, count);
    }

    private boolean validPlayerCount() {
        return !maxPlayersText.isEmpty()
                && HostPlayerLimit.isValidPlayerCount(new BigInteger(maxPlayersText));
    }

    private Component relayOnlyLabel() {
        return Component.translatable(relayOnly ? "mclink.relay_only_enabled" : "mclink.relay_only_disabled");
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
