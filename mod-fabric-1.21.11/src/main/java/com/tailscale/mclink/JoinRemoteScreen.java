package com.tailscale.mclink;

import net.minecraft.client.input.KeyInput;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class JoinRemoteScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget invite;
    private ButtonWidget connect;
    private ButtonWidget lastCode;
    private CheckboxWidget relayOnly;
    private Text status = Text.empty();
    private boolean connecting;
    public JoinRemoteScreen(Screen parent) {
        super(Text.translatable("mclink.join"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        String invitation = invite == null ? "" : invite.getText();
        var rememberedConnection = LastSuccessfulConnection.get();
        boolean forceRelay = relayOnly != null ? relayOnly.isChecked()
                : rememberedConnection != null && rememberedConnection.relayOnly();
        int inputWidth = Math.min(300, Math.max(1, width - 32));
        invite = new TextFieldWidget(textRenderer, (width - inputWidth) / 2, height / 2 - 60, inputWidth, 20,
                Text.translatable("mclink.invite"));
        invite.setMaxLength(8192);
        invite.setPlaceholder(Text.translatable("mclink.invite_hint"));
        invite.setChangedListener(value -> connect.active = value.trim().startsWith("mcl1_"));
        addDrawableChild(invite);
        relayOnly = addDrawableChild(CheckboxWidget.builder(Text.translatable("mclink.relay_only"), textRenderer)
                .pos(width / 2 - 100, height / 2 - 30).checked(forceRelay).build());

        connect = addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.connect"), b -> begin())
                .dimensions(width / 2 - 102, height / 2, 100, 20).build());
        connect.active = false;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> close())
                .dimensions(width / 2 + 2, height / 2, 100, 20).build());
        lastCode = addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.last_invite"), b -> {
                    var previous = LastSuccessfulConnection.get();
                    if (previous != null) begin(previous.invite(), relayOnly.isChecked());
                })
                .dimensions(width / 2 - 102, height / 2 + 26, 204, 20).build());
        lastCode.active = LastSuccessfulConnection.get() != null;
        invite.setText(invitation);
        setInitialFocus(invite);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && connect != null && connect.active && !connecting) {
            begin(); return true;
        }
        return super.keyPressed(input);
    }

    private void begin() {
        begin(invite.getText());
    }

    private void begin(String invitation) {
        begin(invitation, relayOnly.isChecked());
    }

    private void begin(String invitation, boolean forceRelay) {
        if (connecting || invitation == null || invitation.isBlank()) return;
        connecting = true;
        connect.active = false;
        lastCode.active = false;
        relayOnly.active = false;
        invite.setEditable(false);
        status = Text.translatable("mclink.starting");
        McLinkClient.state().join(client, parent, invitation, forceRelay).whenComplete((ignored, error) -> {
            if (error != null) {
                client.execute(() -> {
                    if (client.currentScreen != this) return;
                    connecting = false;
                    status = Text.translatable("mclink.error_connect", Text.translatable(FailureGuide.key(error)));
                    invite.setEditable(true);
                    connect.active = invite.getText().trim().startsWith("mcl1_");
                    lastCode.active = LastSuccessfulConnection.get() != null;
                    relayOnly.active = true;
                });
            }
        });
    }

    @Override
    public void tick() {
        super.tick();
        if (connecting) status = Text.translatable(McLinkClient.state().phaseKey());
    }

    @Override
    public void close() {
        McLinkClient.state().stop();
        client.setScreen(parent);
    }


    private void renderStatus(DrawContext context, int y, int rows) {
        var lines = textRenderer.wrapLines(status, Math.max(1, Math.min(300, width - 32)));
        int visible = Math.min(rows, lines.size());
        if (visible == 0) return;
        context.fill(Math.max(0, width / 2 - 154), y - 3, Math.min(width, width / 2 + 154), y + visible * 10, 0xFF161616);
        for (int i = 0; i < visible; i++) {
            context.drawCenteredTextWithShadow(textRenderer, lines.get(i), width / 2, y + i * 10, 0xFFDDDDDD);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 88, 0xFFFFFFFF);
        renderStatus(context, height / 2 + 54, 2);
    }
}
