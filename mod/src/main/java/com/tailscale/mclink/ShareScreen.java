package com.tailscale.mclink;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class ShareScreen extends Screen {
    private final Screen parent;
    private String invite;
    private boolean started;
    private Text status = Text.translatable("mclink.starting");
    public ShareScreen(Screen parent) {
        super(Text.translatable("mclink.share"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.copy"), b -> {
            if (invite != null) {
                client.keyboard.setClipboard(invite);
            }
        }).dimensions(width / 2 - 102, height / 2 + 34, 100, 20).build()).active = invite != null;
        addDrawableChild(ButtonWidget.builder(Text.translatable("mclink.stop"), b -> {
            McLinkClient.state().stop();
            close();
        }).dimensions(width / 2 + 2, height / 2 + 34, 100, 20).build());
        if (!started) {
            started = true;
            McLinkClient.state().share(client).whenComplete((value, error) -> client.execute(() -> {
                if (error != null) {
                    status = Text.literal("Could not share: " + rootMessage(error));
                } else {
                    invite = value;
                    status = Text.translatable("mclink.sharing");
                    clearAndInit();
                }
            }));
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 62, 0xffffff);
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height / 2 - 34, 0xdddddd);
        if (invite != null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(shorten(invite)),
                    width / 2, height / 2 - 8, 0xaaaaaa);
        }
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
