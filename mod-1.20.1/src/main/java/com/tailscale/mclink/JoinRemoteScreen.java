package com.tailscale.mclink;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class JoinRemoteScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget invite;
    private ButtonWidget connect;
    private Text status = Text.empty();
    public JoinRemoteScreen(Screen parent) {
        super(Text.translatable("mclink.join"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        invite = new TextFieldWidget(textRenderer, width / 2 - 150, height / 2 - 22, 300, 20,
                Text.translatable("mclink.invite"));
        invite.setMaxLength(8192);
        invite.setPlaceholder(Text.translatable("mclink.invite_hint"));
        invite.setChangedListener(value -> connect.active = value.trim().startsWith("mcl1_"));
        addDrawableChild(invite);
        connect = addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.connect"), b -> begin())
                .dimensions(width / 2 - 102, height / 2 + 12, 100, 20).build());
        connect.active = false;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> close())
                .dimensions(width / 2 + 2, height / 2 + 12, 100, 20).build());
        setInitialFocus(invite);
    }

    private void begin() {
        connect.active = false;
        invite.setEditable(false);
        status = Text.translatable("mclink.starting");
        McLinkClient.state().join(client, parent, invite.getText()).whenComplete((ignored, error) -> {
            if (error != null) {
                client.execute(() -> {
                    status = Text.literal("Could not connect: " + ShareScreen.rootMessage(error));
                    invite.setEditable(true);
                    connect.active = true;
                });
            }
        });
    }

    @Override
    public void close() {
        McLinkClient.state().stop();
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 58, 0xffffff);
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height / 2 + 46, 0xffaaaa);
    }
}
