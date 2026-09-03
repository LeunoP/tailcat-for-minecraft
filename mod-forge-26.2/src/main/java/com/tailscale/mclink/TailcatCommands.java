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
import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class TailcatCommands {
    private TailcatCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection selection) {
        dispatcher.register(
            Commands.literal("tailcat")
                .executes(ctx -> handleStatusOrInvite(ctx.getSource()))
                .then(Commands.literal("invite")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(ctx -> showInvite(ctx.getSource())))
                .then(Commands.literal("status")
                    .executes(ctx -> showStatus(ctx.getSource())))
        );
    }

    private static int handleStatusOrInvite(CommandSourceStack source) {
        return showStatus(source);
    }

    private static int showInvite(CommandSourceStack source) {
        String invite = ServerShareHandler.getCurrentInvite();
        if (invite != null && !invite.isBlank()) {
            Component inviteComponent = Component.literal(invite)
                .withStyle(Style.EMPTY
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.CopyToClipboard(invite))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("클릭하여 초대 코드 복사"))));

            source.sendSuccess(() -> Component.literal("[Tailcat] 초대 코드: ")
                .withStyle(ChatFormatting.GREEN)
                .append(inviteComponent), false);

            source.sendSuccess(() -> Component.literal("[Tailcat] (코드를 클릭하여 복사하거나 'tailcat_invite.txt'를 확인하세요)")
                .withStyle(ChatFormatting.GRAY), false);
        } else {
            source.sendFailure(Component.literal("[Tailcat] Tailcat 터널이 비활성화 상태이거나 초대 코드가 준비되지 않았습니다."));
        }
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        ServerPlayer executingPlayer = source.getPlayer();
        boolean isAdmin = Commands.LEVEL_ADMINS.check(source.permissions());

        if (isAdmin) {
            source.sendSuccess(() -> Component.literal("=== [Tailcat 연결 상태 (전체)] ===").withStyle(ChatFormatting.GOLD), false);
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                source.sendSuccess(() -> formatPlayerLine(player), false);
            }
        } else if (executingPlayer != null) {
            source.sendSuccess(() -> formatPlayerLine(executingPlayer), false);
        } else {
            source.sendSuccess(() -> Component.literal("콘솔에서는 모든 유저 정보를 확인하려면 관리자 권한이 필요합니다.").withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static Component formatPlayerLine(ServerPlayer player) {
        int ping = player.connection != null ? player.connection.latency() : 0;
        String connType = resolveConnectionType(player);

        ChatFormatting typeColor = "다이렉트".equals(connType) ? ChatFormatting.AQUA : ChatFormatting.YELLOW;

        return Component.literal("[ ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" ] ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(ping + "ms").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" / ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(connType).withStyle(typeColor));
    }

    private static String resolveConnectionType(ServerPlayer player) {
        if (player.connection == null) return "알 수 없음";
        SocketAddress sa = player.connection.getRemoteAddress();
        if (sa instanceof InetSocketAddress isa) {
            String ip = isa.getAddress().getHostAddress();
            if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
                return "다이렉트"; // P2P Tailcat virtual tunnel
            }
            if (!ip.startsWith("100.") && !ip.startsWith("fd7a:")) {
                return "다이렉트"; // Direct public/LAN IP
            }
        }
        return "릴레이";
    }
}
