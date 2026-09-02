package com.tailscale.mclink;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class JoinRemoteScreen extends Screen {
    private final Screen parent;
    private EditBox invite;
    private Button connect;
    private Component status = Component.empty();

    public JoinRemoteScreen(Screen parent) {
        super(Component.translatable("mclink.join"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        invite = new EditBox(this.font, width / 2 - 150, height / 2 - 22, 300, 20,
                Component.translatable("mclink.invite"));
        invite.setMaxLength(8192);
        invite.setHint(Component.translatable("mclink.invite_hint"));
        invite.setResponder(value -> connect.active = value.trim().startsWith("mcl1_"));
        addRenderableWidget(invite);

        connect = addRenderableWidget(Button.builder(Component.translatable("mclink.connect"), b -> begin())
                .bounds(width / 2 - 102, height / 2 + 12, 100, 20).build());
        connect.active = false;

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 + 2, height / 2 + 12, 100, 20).build());

        setInitialFocus(invite);
    }

    private void begin() {
        connect.active = false;
        invite.setEditable(false);
        status = Component.translatable("mclink.starting");
        McLinkClient.state().join(minecraft, parent, invite.getValue()).whenComplete((ignored, error) -> {
            if (error != null && minecraft != null) {
                minecraft.execute(() -> {
                    status = Component.literal("Could not connect: " + ShareScreen.rootMessage(error));
                    invite.setEditable(true);
                    connect.active = true;
                });
            }
        });
    }

    @Override
    public void onClose() {
        McLinkClient.state().stop();
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);
        extractor.centeredText(this.font, title, width / 2, height / 2 - 58, 0xFFFFFFFF);
        extractor.centeredText(this.font, status, width / 2, height / 2 + 46, 0xFFFFAAAA);
    }
}
