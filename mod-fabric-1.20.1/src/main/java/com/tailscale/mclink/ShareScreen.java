package com.tailscale.mclink;

import java.math.BigInteger;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;
import org.lwjgl.glfw.GLFW;

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
    private int playerScrollOffset = 0;
    private static final int VISIBLE_PLAYER_ROWS = 2;

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
                UUID hostId = client.player != null ? client.player.getUuid() : null;
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

        Thread stopThread = new Thread(() -> {
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
        }, "mclink-stop");
        stopThread.setDaemon(true);
        stopThread.start();
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
        renderBackground(context);
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

            // Connected Players with Ping & Connection Mode
            ScreenState state = McLinkClient.state();
            ScreenState.TransportMode mode = state.getTransportMode();

            List<ServerPlayerEntity> guests = getGuestPlayers();
            if (guests.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("mclink.no_players"), centerX, currentY + 70, 0xFF888888);
            } else {
                Text countText = Text.translatable("mclink.connected_players", guests.size());
                context.drawCenteredTextWithShadow(this.textRenderer, countText, centerX, currentY + 68, 0xFFFFFFFF);
                playerScrollOffset = Math.min(playerScrollOffset, Math.max(0, guests.size() - VISIBLE_PLAYER_ROWS));
                int playerY = currentY + 84;
                for (int i = playerScrollOffset; i < Math.min(guests.size(), playerScrollOffset + VISIBLE_PLAYER_ROWS); i++) {
                    ServerPlayerEntity player = guests.get(i);
                    int latency = player.pingMilliseconds;
                    Formatting pingFormat = latency < 80 ? Formatting.GREEN : (latency < 160 ? Formatting.YELLOW : Formatting.RED);

                    MutableText line = Text.literal("• " + player.getName().getString() + "   ")
                            .formatted(Formatting.WHITE)
                            .append(Text.literal(latency + "ms").formatted(pingFormat))
                            .append(Text.literal(" / " + state.routeLabel(player.networkHandler.getConnectionAddress())));

                    context.drawCenteredTextWithShadow(this.textRenderer, line, centerX, playerY, 0xFFFFFFFF);
                    playerY += 14;
                }
                if (guests.size() > VISIBLE_PLAYER_ROWS) {
                    context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("mclink.scroll_players"), centerX,
                            currentY + 84 + VISIBLE_PLAYER_ROWS * 14 + 2, 0xFFAAAAAA);
                }
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
        var state = McLinkClient.state();
        var display = hostingLayout && state.isHosting()
                ? (getGuestPlayers().isEmpty()
                    ? Text.translatable("mclink.waiting_players")
                    : Text.translatable("mclink.connection_path", state.getTransportMode().label()))
                : status;
        renderNote(context, display, y, rows);
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

    private boolean scrollPlayers(int amount) {
        int maxOffset = Math.max(0, getGuestPlayers().size() - VISIBLE_PLAYER_ROWS);
        if (maxOffset == 0) return false;
        playerScrollOffset = Math.max(0, Math.min(maxOffset, playerScrollOffset + amount));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (McLinkClient.state().isHosting() && mouseX >= 0 && mouseX < width
                && mouseY >= height / 2.0 - 6 && mouseY < height / 2.0 + 42
                && amount != 0 && scrollPlayers(amount > 0 ? -1 : 1)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (McLinkClient.state().isHosting() && getGuestPlayers().size() > VISIBLE_PLAYER_ROWS) {
            if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_PAGE_UP) return scrollPlayers(-1);
            if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) return scrollPlayers(1);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private List<ServerPlayerEntity> getGuestPlayers() {
        if (client == null) return List.of();
        IntegratedServer server = client.getServer();
        if (server == null) return List.of();
        UUID hostId = client.player != null ? client.player.getUuid() : null;
        return server.getPlayerManager().getPlayerList().stream()
                .filter(p -> McLinkClient.state().owns(p.networkHandler.getConnectionAddress()))
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
