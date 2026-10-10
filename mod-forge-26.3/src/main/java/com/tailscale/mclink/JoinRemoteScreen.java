package com.tailscale.mclink;

import net.minecraft.client.input.KeyEvent;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class JoinRemoteScreen extends Screen {
    private final Screen parent;
    private EditBox invite;
    private Button connect;
    private Button lastCode;
    private Checkbox relayOnly;
    private Component status = Component.empty();
    private boolean connecting;

    public JoinRemoteScreen(Screen parent) {
        super(Component.translatable("mclink.join"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        String invitation = invite == null ? "" : invite.getValue();
        var rememberedConnection = LastSuccessfulConnection.get();
        boolean forceRelay = relayOnly != null ? relayOnly.selected()
                : rememberedConnection != null && rememberedConnection.relayOnly();
        int inputWidth = Math.min(300, Math.max(1, width - 32));
        invite = new EditBox(this.font, (width - inputWidth) / 2, height / 2 - 60, inputWidth, 20,
                Component.translatable("mclink.invite"));
        invite.setMaxLength(8192);
        invite.setHint(Component.translatable("mclink.invite_hint"));
        invite.setResponder(value -> connect.active = value.trim().startsWith("mcl1_"));
        addRenderableWidget(invite);

        relayOnly = addRenderableWidget(Checkbox.builder(Component.translatable("mclink.relay_only"), font)
                .pos(width / 2 - 100, height / 2 - 30).selected(forceRelay).build());

        connect = addRenderableWidget(Button.builder(Component.translatable("mclink.connect"), b -> begin())
                .bounds(width / 2 - 102, height / 2, 100, 20).build());
        connect.active = false;

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 + 2, height / 2, 100, 20).build());
        lastCode = addRenderableWidget(Button.builder(Component.translatable("mclink.last_invite"), b -> {
                    var previous = LastSuccessfulConnection.get();
                    if (previous != null) begin(previous.invite(), relayOnly.selected());
                })
                .bounds(width / 2 - 102, height / 2 + 26, 204, 20).build());
        lastCode.active = LastSuccessfulConnection.get() != null;

        invite.setValue(invitation);
        setInitialFocus(invite);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER)
                && connect != null && connect.active && !connecting) {
            begin(); return true;
        }
        return super.keyPressed(event);
    }

    private void begin() {
        begin(invite.getValue());
    }

    private void begin(String invitation) {
        begin(invitation, relayOnly.selected());
    }

    private void begin(String invitation, boolean forceRelay) {
        if (connecting || invitation == null || invitation.isBlank()) return;
        connecting = true;
        connect.active = false;
        lastCode.active = false;
        relayOnly.active = false;
        invite.setEditable(false);
        status = Component.translatable("mclink.starting");
        McLinkClient.state().join(minecraft, parent, invitation, forceRelay).whenComplete((ignored, error) -> {
            if (error != null && minecraft != null) {
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() != this) return;
                    connecting = false;
                    status = Component.translatable("mclink.error_connect", Component.translatable(FailureGuide.key(error)));
                    invite.setEditable(true);
                    connect.active = invite.getValue().trim().startsWith("mcl1_");
                    lastCode.active = LastSuccessfulConnection.get() != null;
                    relayOnly.active = true;
                });
            }
        });
    }

    @Override
    public void tick() {
        super.tick();
        if (connecting) status = Component.translatable(McLinkClient.state().phaseKey());
    }

    @Override
    public void onClose() {
        McLinkClient.state().stop();
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }


    private void renderStatus(GuiGraphicsExtractor extractor, int y, int rows) {
        var lines = font.split(status, Math.max(1, Math.min(300, width - 32)));
        int visible = Math.min(rows, lines.size());
        if (visible == 0) return;
        extractor.fill(Math.max(0, width / 2 - 154), y - 3, Math.min(width, width / 2 + 154), y + visible * 10, 0xFF161616);
        for (int i = 0; i < visible; i++) {
            extractor.centeredText(font, lines.get(i), width / 2, y + i * 10, 0xFFDDDDDD);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);
        extractor.centeredText(this.font, title, width / 2, height / 2 - 88, 0xFFFFFFFF);
        renderStatus(extractor, height / 2 + 54, 2);
    }
}
