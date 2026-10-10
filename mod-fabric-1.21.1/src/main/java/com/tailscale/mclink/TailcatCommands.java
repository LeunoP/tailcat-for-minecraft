package com.tailscale.mclink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class TailcatCommands {
    private TailcatCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                net.minecraft.command.CommandRegistryAccess buildContext,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
            CommandManager.literal("tailcat")
                .executes(ctx -> showStatus(ctx.getSource()))
                .then(CommandManager.literal("invite")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> showInvite(ctx.getSource())))
                .then(CommandManager.literal("start")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> operate(ctx.getSource(), "start")))
                .then(CommandManager.literal("stop")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> operate(ctx.getSource(), "stop")))
                .then(CommandManager.literal("restart")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> operate(ctx.getSource(), "restart")))
                .then(CommandManager.literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
        );
    }

    private static int showInvite(ServerCommandSource source) {
        String invite = ServerShareHandler.getCurrentInvite();
        if (invite != null && !invite.isBlank()) {
            Text inviteComponent = Text.literal(invite).styled(style -> style
                .withColor(Formatting.YELLOW)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, invite))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.copy_hint")))));

            source.sendFeedback(() -> Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.invite_label"))
                .formatted(Formatting.GREEN)
                .append(inviteComponent), false);

            source.sendFeedback(() -> Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.invite_hint"))
                .formatted(Formatting.GRAY), false);
        } else {
            source.sendError(Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.inactive")));
        }
        return 1;
    }

    private static int showStatus(ServerCommandSource source) {
        ServerPlayerEntity executingPlayer = source.getPlayer();
        boolean isAdmin = source.hasPermissionLevel(2);

        if (isAdmin) {
            source.sendFeedback(() -> Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.status_title")).formatted(Formatting.GOLD), false);
            int count = 0;
            for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                if (isTailcatPlayer(player)) {
                    source.sendFeedback(() -> formatPlayerLine(player), false);
                    count++;
                }
            }
            if (count == 0) {
                source.sendFeedback(() -> Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.no_players")).formatted(Formatting.GRAY), false);
            }
        } else if (executingPlayer != null) {
            if (isTailcatPlayer(executingPlayer)) {
                source.sendFeedback(() -> formatPlayerLine(executingPlayer), false);
            } else {
                source.sendFeedback(() -> Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.not_connected")).formatted(Formatting.GRAY), false);
            }
        } else {
            source.sendFeedback(() -> Text.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.console_permission")).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int operate(ServerCommandSource source, String action) {
        ServerShareHandler.operate(source.getServer(), action).whenComplete((result, error) ->
                source.getServer().execute(() -> source.sendFeedback(() -> Text.literal(
                        ServerMessages.response(ServerShareHandler.language(), action, error == null, result)), false)));
        return 1;
    }

    private static boolean isTailcatPlayer(ServerPlayerEntity player) {
        if (player.networkHandler == null) return false;
        SocketAddress sa = player.networkHandler.getConnectionAddress();
        return ServerShareHandler.owns(sa);
    }

    private static Text formatPlayerLine(ServerPlayerEntity player) {
        int ping = player.networkHandler != null ? player.networkHandler.getLatency() : 0;

        return Text.literal("[ ")
            .formatted(Formatting.GRAY)
            .append(Text.literal(player.getName().getString()).formatted(Formatting.GREEN))
            .append(Text.literal(" ] ").formatted(Formatting.GRAY))
            .append(Text.literal(ping + "ms").formatted(Formatting.WHITE));
    }
}
