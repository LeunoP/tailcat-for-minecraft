package com.tailscale.mclink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class TailcatCommands {
    private TailcatCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                net.minecraft.command.CommandRegistryAccess buildContext,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
            CommandManager.literal("tailcat")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> showInvite(ctx.getSource()))
                .then(CommandManager.literal("invite").executes(ctx -> showInvite(ctx.getSource())))
                .then(CommandManager.literal("status").executes(ctx -> showStatus(ctx.getSource())))
        );
    }

    private static int showInvite(ServerCommandSource source) {
        String invite = ServerShareHandler.getCurrentInvite();
        if (invite != null && !invite.isBlank()) {
            Text inviteComponent = Text.literal(invite).styled(style -> style
                .withColor(Formatting.YELLOW)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, invite))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy invite code"))));

            source.sendFeedback(() -> Text.literal("[Tailcat] Invitation Code: ")
                .formatted(Formatting.GREEN)
                .append(inviteComponent), false);

            source.sendFeedback(() -> Text.literal("[Tailcat] (Click the code to copy or check 'tailcat_invite.txt')")
                .formatted(Formatting.GRAY), false);
        } else {
            source.sendError(Text.literal("[Tailcat] Tailcat tunnel is not active or invitation code is not ready yet."));
        }
        return 1;
    }

    private static int showStatus(ServerCommandSource source) {
        String invite = ServerShareHandler.getCurrentInvite();
        boolean active = invite != null && !invite.isBlank();
        source.sendFeedback(() -> Text.literal("[Tailcat] Status: ")
            .formatted(Formatting.GREEN)
            .append(Text.literal(active ? "ACTIVE" : "INACTIVE")
                .formatted(active ? Formatting.GREEN : Formatting.RED)), false);
        return 1;
    }
}
