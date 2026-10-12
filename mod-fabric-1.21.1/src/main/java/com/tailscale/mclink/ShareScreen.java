package com.tailscale.mclink;

import java.math.BigInteger;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class ShareScreen extends Screen {
    private final Screen parent;
    private String invite;
    private boolean starting;
    private boolean hostingLayout;
    private Text status;
    private boolean relayOnly;
    private String maxPlayersText = "8";
    private TextFieldWidget playerCountInput;
    private ButtonWidget startButton;
    private boolean showInvite = false;
    private ButtonWidget copyButton;
    private int copyFeedbackTicks = 0;

    public ShareScreen(Screen parent) {
        super(Text.translatable("mclink.share"));
        this.parent = parent;
        var remembered = HostPreferences.SESSION.get();
        relayOnly = remembered.relayOnly();
        maxPlayersText = Integer.toString(remembered.maxPlayers());
        this.invite = McLinkClient.state().getInvite();
        if (McLinkClient.state().isHosting()) {
            this.status = Text.translatable("mclink.sharing");
        } else {
            this.status = Text.translatable("mclink.ready");
        }
    }

    @Override
    public boolean shouldPause() {
        ScreenState state = McLinkClient.state();
        return !starting && !state.isHosting() && !state.isRestoring();
    }

    @Override
    public void tick() {
        super.tick();
        if (hostingLayout && !starting && !McLinkClient.state().isHosting()) {
            invite = null;
            status = Text.translatable(McLinkClient.state().phaseKey());
            clearAndInit();
        }
        if (starting) status = Text.translatable(McLinkClient.state().phaseKey());
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
        startButton = null;
        boolean hosting = McLinkClient.state().isHosting() && !starting;
        hostingLayout = hosting;

        if (hosting) {
            int buttonY = height / 2 + 48;
            addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.connection_info"), b -> {
                client.setScreen(new ConnectionStatusScreen(this));
            }).dimensions(width / 2 - 102, height / 2 + 16, 204, 20).build());
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
                int inviteY = height / 2 - 54;

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
                                status = Text.translatable("mclink.error_share", Text.translatable(FailureGuide.key(error)));
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
            int buttonY = height / 2 + 40;
            playerCountInput = new TextFieldWidget(textRenderer, width / 2 - 102, height / 2 + 12, 204, 20,
                    Text.translatable("mclink.next_share_capacity"));
            playerCountInput.setMaxLength(10);
            playerCountInput.setTextPredicate(value -> value.matches("[0-9]*"));
            playerCountInput.setPlaceholder(Text.translatable("mclink.max_players_hint"));
            playerCountInput.setText(maxPlayersText);
            playerCountInput.setEditable(!starting);
            playerCountInput.setChangedListener(value -> {
                maxPlayersText = value;
                if (startButton != null) {
                    startButton.active = !starting && validPlayerCount();
                }
            });
            addDrawableChild(playerCountInput);

            ButtonWidget relayButton = addDrawableChild(ButtonWidget.builder(relayOnlyLabel(), b -> {
                relayOnly = !relayOnly;
                rememberHostSettings();
                clearAndInit();
            }).dimensions(width / 2 - 102, height / 2 - 24, 204, 20).build());
            relayButton.active = !starting;

            startButton = addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.start"), b -> {
                startSharing();
            }).dimensions(width / 2 - 102, buttonY, 100, 20).build());
            startButton.active = !starting && validPlayerCount();

            addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), b -> {
                close();
            }).dimensions(width / 2 + 2, buttonY, 100, 20).build());
            setInitialFocus(playerCountInput);
        }
    }

    private void startSharing() {
        if (starting || !validPlayerCount()) return;
        BigInteger maxPlayers = new BigInteger(maxPlayersText);
        rememberHostSettings();
        starting = true;
        status = Text.translatable("mclink.starting");
        clearAndInit();

        McLinkClient.state().share(client, relayOnly, maxPlayers).whenComplete((value, error) -> {
            if (client != null) {
                client.execute(() -> {
                    starting = false;
                    if (error != null) {
                        status = Text.translatable("mclink.error_share", Text.translatable(FailureGuide.key(error)));
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
        Runnable stopCurrentSession = McLinkClient.state().stopAction();
        if (client != null) {
            IntegratedServer server = client.getServer();
            if (server != null) {
                Text disconnectReason = Text.translatable("mclink.stopped_message");
                server.execute(() -> {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (McLinkClient.state().owns(player.networkHandler.getConnectionAddress())) {
                        player.networkHandler.disconnect(disconnectReason);
                    }
                }
                });
            }
        }

        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {}
            if (client != null) client.execute(stopCurrentSession);
            if (client != null) {
                client.execute(() -> {
                    invite = null;
                    status = Text.translatable("mclink.stopped_status");
                    clearAndInit();
                });
            }
        });
    }

    @Override
    public void close() {
        rememberHostSettings();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int centerX = width / 2;
        boolean hosting = hostingLayout;

        if (hosting) {
            int currentY = height / 2 - 86;

            // Title
            context.drawCenteredTextWithShadow(this.textRenderer, title, centerX, currentY, 0xFFFFFFFF);

            // Status
            renderStatus(context, currentY + 16, 1);
            renderNote(context, Text.translatable("mclink.current_capacity", currentMaxPlayers()), currentY + 50, 1);

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

        } else {
            int currentY = height / 2 - 84;
            context.drawCenteredTextWithShadow(this.textRenderer, title, centerX, currentY, 0xFFFFFFFF);
            renderStatus(context, currentY + 20, 2);
            renderNote(context, Text.translatable("mclink.current_capacity", currentMaxPlayers()), height / 2 - 38, 1);
            renderNote(context, Text.translatable("mclink.next_share_capacity"), height / 2 + 1, 1);
        }
        if (preservesExistingLan()) {
            renderNote(context, Text.translatable("mclink.existing_lan_kept"), height / 2 + (hosting ? 100 : 66), 2);
        }
    }


    private void renderStatus(DrawContext context, int y, int rows) {
        renderNote(context, status, y, rows);
    }

    private void renderNote(DrawContext context, Text message, int y, int rows) {
        var lines = textRenderer.wrapLines(message, Math.max(1, Math.min(300, width - 32)));
        int visible = Math.min(rows, lines.size());
        if (visible == 0) return;
        for (int i = 0; i < visible; i++) {
            context.drawCenteredTextWithShadow(textRenderer, lines.get(i), width / 2, y + i * 10, 0xFFDDDDDD);
        }
    }

    private int currentMaxPlayers() {
        var server = client == null ? null : client.getServer();
        return server == null ? 0 : server.getPlayerManager().getMaxPlayerCount();
    }

    private boolean preservesExistingLan() {
        var server = client == null ? null : client.getServer();
        if (server == null) return false;
        return McLinkClient.state().isHosting() ? McLinkClient.state().keepsExistingLan() : ((com.tailscale.mclink.mixin.IntegratedServerAccessor) server).mclink$getLanPort() > 0;
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

    private Text relayOnlyLabel() {
        return Text.translatable(relayOnly ? "mclink.relay_only_enabled" : "mclink.relay_only_disabled");
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
