package com.tailscale.mclink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public final class TailcatCommands {
    private TailcatCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection selection) {
        dispatcher.register(
            Commands.literal("tailcat")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(ctx -> showInvite(ctx.getSource()))
                .then(Commands.literal("invite").executes(ctx -> showInvite(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> showStatus(ctx.getSource())))
        );
    }

    private static int showInvite(CommandSourceStack source) {
        String invite = ServerShareHandler.getCurrentInvite();
        if (invite != null && !invite.isBlank()) {
            Component inviteComponent = Component.literal(invite)
                .withStyle(Style.EMPTY
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.CopyToClipboard(invite))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy invite code"))));

            source.sendSuccess(() -> Component.literal("[Tailcat] Invitation Code: ")
                .withStyle(ChatFormatting.GREEN)
                .append(inviteComponent), false);

            source.sendSuccess(() -> Component.literal("[Tailcat] (Click the code to copy or check 'tailcat_invite.txt')")
                .withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendFailure(Component.literal("[Tailcat] Tailcat tunnel is not active or invitation code is not ready yet."));
        }
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        String invite = ServerShareHandler.getCurrentInvite();
        boolean active = invite != null && !invite.isBlank();
        source.sendSuccess(() -> Component.literal("[Tailcat] Status: ")
            .withStyle(ChatFormatting.GREEN)
            .append(Component.literal(active ? "ACTIVE" : "INACTIVE")
                .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.RED)), false);
        return 1;
    }
}
