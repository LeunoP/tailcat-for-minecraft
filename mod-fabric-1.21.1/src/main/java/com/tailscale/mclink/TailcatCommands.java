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
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("클릭하여 초대 코드 복사"))));

            source.sendFeedback(() -> Text.literal("[Tailcat] 초대 코드: ")
                .formatted(Formatting.GREEN)
                .append(inviteComponent), false);

            source.sendFeedback(() -> Text.literal("[Tailcat] (코드를 클릭하여 복사하거나 'tailcat_invite.txt'를 확인하세요)")
                .formatted(Formatting.GRAY), false);
        } else {
            source.sendError(Text.literal("[Tailcat] Tailcat 터널이 비활성화 상태이거나 초대 코드가 준비되지 않았습니다."));
        }
        return 1;
    }

    private static int showStatus(ServerCommandSource source) {
        ServerPlayerEntity executingPlayer = source.getPlayer();
        boolean isAdmin = source.hasPermissionLevel(2);

        if (isAdmin) {
            source.sendFeedback(() -> Text.literal("=== [Tailcat 연결 상태 (전체)] ===").formatted(Formatting.GOLD), false);
            for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                source.sendFeedback(() -> formatPlayerLine(player), false);
            }
        } else if (executingPlayer != null) {
            source.sendFeedback(() -> formatPlayerLine(executingPlayer), false);
        } else {
            source.sendFeedback(() -> Text.literal("콘솔에서는 모든 유저 정보를 확인하려면 관리자 권한이 필요합니다.").formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static Text formatPlayerLine(ServerPlayerEntity player) {
        int ping = player.networkHandler != null ? player.networkHandler.getLatency() : 0;
        String connType = resolveConnectionType(player);
        Formatting typeColor = "다이렉트".equals(connType) ? Formatting.AQUA : Formatting.YELLOW;

        return Text.literal("[ ")
            .formatted(Formatting.GRAY)
            .append(Text.literal(player.getName().getString()).formatted(Formatting.GREEN))
            .append(Text.literal(" ] ").formatted(Formatting.GRAY))
            .append(Text.literal(ping + "ms").formatted(Formatting.WHITE))
            .append(Text.literal(" / ").formatted(Formatting.GRAY))
            .append(Text.literal(connType).formatted(typeColor));
    }

    private static String resolveConnectionType(ServerPlayerEntity player) {
        if (player.networkHandler == null) return "알 수 없음";
        SocketAddress sa = player.networkHandler.getConnectionAddress();
        if (sa instanceof InetSocketAddress isa) {
            String ip = isa.getAddress().getHostAddress();
            if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
                return "다이렉트";
            }
            if (!ip.startsWith("100.") && !ip.startsWith("fd7a:")) {
                return "다이렉트";
            }
        }
        return "릴레이";
    }
}
