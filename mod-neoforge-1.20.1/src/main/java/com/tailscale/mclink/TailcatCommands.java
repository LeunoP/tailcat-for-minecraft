package com.tailscale.mclink;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class TailcatCommands {
    private TailcatCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("tailcat")
                .executes(ctx -> showStatus(ctx.getSource()))
                .then(Commands.literal("invite")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> showInvite(ctx.getSource())))
                .then(Commands.literal("start")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> operate(ctx.getSource(), "start")))
                .then(Commands.literal("stop")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> operate(ctx.getSource(), "stop")))
                .then(Commands.literal("restart")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> operate(ctx.getSource(), "restart")))
                .then(Commands.literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
        );
    }

    private static int showInvite(CommandSourceStack source) {
        String invite = ServerShareHandler.getCurrentInvite();
        if (invite != null && !invite.isBlank()) {
            Component inviteComponent = Component.literal(invite)
                .withStyle(Style.EMPTY
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, invite))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.copy_hint")))));

            source.sendSuccess(() -> Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.invite_label"))
                .withStyle(ChatFormatting.GREEN)
                .append(inviteComponent), false);

            source.sendSuccess(() -> Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.invite_hint"))
                .withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendFailure(Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.inactive")));
        }
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        ServerPlayer executingPlayer = source.getPlayer();
        boolean isAdmin = source.hasPermission(2);

        if (isAdmin) {
            source.sendSuccess(() -> Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.status_title")).withStyle(ChatFormatting.GOLD), false);
            int count = 0;
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                if (isTailcatPlayer(player)) {
                    source.sendSuccess(() -> formatPlayerLine(player), false);
                    count++;
                }
            }
            if (count == 0) {
                source.sendSuccess(() -> Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.no_players")).withStyle(ChatFormatting.GRAY), false);
            }
        } else if (executingPlayer != null) {
            if (isTailcatPlayer(executingPlayer)) {
                source.sendSuccess(() -> formatPlayerLine(executingPlayer), false);
            } else {
                source.sendSuccess(() -> Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.not_connected")).withStyle(ChatFormatting.GRAY), false);
            }
        } else {
            source.sendSuccess(() -> Component.literal(ServerMessages.text(ServerShareHandler.language(), "mclink.server.console_permission")).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int operate(CommandSourceStack source, String action) {
        ServerShareHandler.operate(source.getServer(), action).whenComplete((result, error) ->
                source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(
                        ServerMessages.response(ServerShareHandler.language(), action, error == null, result)), false)));
        return 1;
    }

    private static boolean isTailcatPlayer(ServerPlayer player) {
        if (player.connection == null) return false;
        SocketAddress sa = player.connection.getRemoteAddress();
        return ServerShareHandler.owns(sa);
    }

    private static Component formatPlayerLine(ServerPlayer player) {
        int ping = player.connection != null ? player.latency : 0;

        SocketAddress address = player.connection == null ? null : player.connection.getRemoteAddress();
        String route = RouteStatus.commandLabel(ServerShareHandler.connectionStatus(address), ServerShareHandler.language());

        return Component.literal("[ ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" ] ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(route + " ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(ping + "ms").withStyle(ChatFormatting.WHITE));
    }
}
