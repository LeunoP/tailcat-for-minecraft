package com.tailscale.mclink;

import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

public final class ConnectionStatusScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int LEFT = 12;
    private final Screen parent;
    private int scrollOffset;
    private int visibleRows = 1;
    private int rowHeight = ROW_HEIGHT;
    private boolean compactLayout;
    private int listTop;
    private int listBottom;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;

    public ConnectionStatusScreen(Screen parent) {
        super(Component.translatable("mclink.status_title"));
        this.parent = parent;
    }

    static boolean isAvailable(Minecraft client) {
        return client.level != null && RouteStatusCache.hasSnapshot();
    }

    @Override
    protected void init() {
        compactLayout = width < 410;
        rowHeight = compactLayout ? 32 : ROW_HEIGHT;
        listTop = compactLayout ? 98 : 79;
        listBottom = Math.max(listTop + rowHeight, height - 42);
        visibleRows = Math.max(1, (listBottom - listTop) / rowHeight);
        clampScroll();
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(width / 2 - 102, height - 28, 204, 20).build());
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x80000000);
        super.render(graphics, mouseX, mouseY, delta);
        renderContents(graphics, mouseX, mouseY);
    }


    private void renderContents(GuiGraphics graphics, int mouseX, int mouseY) {
        List<RouteStatus.PlayerRoute> players = players();
        boolean hasSnapshot = Minecraft.getInstance().level != null && RouteStatusCache.hasSnapshot();
        boolean stale = hasSnapshot && RouteStatusCache.isStale();
        String ownName = ownName();
        RouteStatus.PlayerRoute ownRoute = findPlayer(players, ownName);
        if (compactLayout) {
            renderCompactContents(graphics, players, hasSnapshot, stale, ownName, ownRoute, mouseX, mouseY);
            return;
        }

        int contentRight = Math.max(LEFT + 1, width - 18);
        int pingWidth = Math.min(76, Math.max(54, width / 6));
        int pingX = contentRight - pingWidth;
        int routeWidth = Math.min(164, Math.max(72, width * 36 / 100));
        int routeX = Math.max(LEFT + 34, pingX - 6 - routeWidth);
        int actualRouteWidth = Math.max(1, pingX - 6 - routeX);
        int nameWidth = Math.max(1, routeX - LEFT - 6);

        drawCentered(graphics, title.getString(), width / 2, 12, 0xFFFFFFFF);
        drawText(graphics, Component.translatable("mclink.status_own").getString(), LEFT, 37, nameWidth, 0xFFAAAAAA);

        String ownStatus = stale
                ? Component.translatable("mclink.status_disconnected").getString()
                : !hasSnapshot || ownRoute == null
                    ? Component.translatable("mclink.status_waiting").getString()
                    : routeLabel(ownRoute, false, ownRoute.playerName().equals(ownName) && McLinkClient.state().isHosting());
        int ownColor = stale ? 0xFFFF5555 : !hasSnapshot || ownRoute == null ? 0xFFAAAAAA
                : routeColor(ownRoute.status(), false, McLinkClient.state().isHosting());
        String ownPing = stale || ownRoute == null
                ? Component.translatable("mclink.status_ping_unavailable").getString()
                : pingLabel(ownRoute.pingMillis(), false);
        drawText(graphics, ownStatus, routeX, 37, actualRouteWidth, ownColor);
        drawText(graphics, ownPing, pingX, 37, pingWidth,
                stale || ownRoute == null || ownRoute.pingMillis() < 0 ? 0xFFAAAAAA : 0xFFDDDDDD);

        int headerY = 59;
        drawText(graphics, Component.translatable("mclink.status_player_column").getString(), LEFT, headerY, nameWidth, 0xFFAAAAAA);
        drawText(graphics, Component.translatable("mclink.status_route_column").getString(), routeX, headerY, actualRouteWidth, 0xFFAAAAAA);
        drawText(graphics, Component.translatable("mclink.status_ping_column").getString(), pingX, headerY, pingWidth, 0xFFAAAAAA);
        graphics.fill(LEFT, 74, contentRight, 75, 0x80555555);

        if (players.isEmpty()) {
            String empty = Component.translatable(stale ? "mclink.status_disconnected" : "mclink.status_waiting").getString();
            drawCentered(graphics, empty, width / 2, listTop + Math.max(0, (listBottom - listTop) / 2 - 4), 0xFFDDDDDD);
            return;
        }

        clampScroll();
        int count = Math.min(visibleRows, players.size() - scrollOffset);
        for (int row = 0; row < count; row++) {
            RouteStatus.PlayerRoute player = players.get(scrollOffset + row);
            boolean self = player.playerName().equals(ownName);
            int y = listTop + row * rowHeight + 5;
            String playerName = self
                    ? player.playerName() + " (" + Component.translatable("mclink.status_self").getString() + ")"
                    : player.playerName();
            String route = routeLabel(player, stale, self && McLinkClient.state().isHosting());
            String ping = pingLabel(player.pingMillis(), stale);
            drawText(graphics, playerName, LEFT, y, nameWidth, self ? 0xFFFFFFFF : 0xFFDDDDDD);
            drawText(graphics, route, routeX, y, actualRouteWidth,
                    routeColor(player.status(), stale, self && McLinkClient.state().isHosting()));
            drawText(graphics, ping, pingX, y, pingWidth,
                    stale || player.pingMillis() < 0 ? 0xFFAAAAAA : 0xFFDDDDDD);
        }
        drawScrollbar(graphics, players.size(), mouseX, mouseY);
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= 0 && mouseX < width && mouseY >= listTop && mouseY < listBottom
                && horizontalAmount == 0 && verticalAmount != 0 && scroll(verticalAmount > 0 ? -1 : 1)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (click.button() == 0 && beginScrollbarDrag(click.x(), click.y())) return true;
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (draggingScrollbar && click.button() == 0) {
            updateScrollFromMouse(click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_UP) return scroll(-1);
        if (key == InputConstants.KEY_DOWN) return scroll(1);
        if (key == InputConstants.KEY_PAGEUP) return scroll(-visibleRows);
        if (key == InputConstants.KEY_PAGEDOWN) return scroll(visibleRows);
        return super.keyPressed(event);
    }

    private void drawScrollbar(GuiGraphics graphics, int playerCount, int mouseX, int mouseY) {
        int maximum = Math.max(0, playerCount - visibleRows);
        if (maximum == 0) return;
        int x = width - 12;
        int trackHeight = Math.max(1, listBottom - listTop);
        int thumbHeight = Math.min(trackHeight, Math.max(12, trackHeight * visibleRows / playerCount));
        int travel = trackHeight - thumbHeight;
        int thumbY = listTop + (travel == 0 ? 0 : (int) ((long) travel * scrollOffset / maximum));
        graphics.fill(x, listTop, x + 5, listBottom, 0x80333333);
        int thumbColor = mouseX >= x - 2 && mouseX <= x + 7 && mouseY >= thumbY && mouseY <= thumbY + thumbHeight
                ? 0xFFE0E0E0 : 0xFFAAAAAA;
        graphics.fill(x, thumbY, x + 5, thumbY + thumbHeight, thumbColor);
    }

    private boolean beginScrollbarDrag(double mouseX, double mouseY) {
        int maximum = Math.max(0, players().size() - visibleRows);
        int x = width - 12;
        if (maximum == 0 || mouseX < x - 2 || mouseX > x + 7 || mouseY < listTop || mouseY > listBottom) return false;
        int trackHeight = Math.max(1, listBottom - listTop);
        int thumbHeight = Math.min(trackHeight, Math.max(12, trackHeight * visibleRows / Math.max(1, players().size())));
        int travel = trackHeight - thumbHeight;
        int thumbY = listTop + (travel == 0 ? 0 : (int) ((long) travel * scrollOffset / maximum));
        scrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight
                ? (int) mouseY - thumbY : thumbHeight / 2;
        draggingScrollbar = true;
        updateScrollFromMouse(mouseY);
        return true;
    }

    private void updateScrollFromMouse(double mouseY) {
        int maximum = Math.max(0, players().size() - visibleRows);
        int trackHeight = Math.max(1, listBottom - listTop);
        int thumbHeight = Math.min(trackHeight, Math.max(12, trackHeight * visibleRows / Math.max(1, players().size())));
        int travel = trackHeight - thumbHeight;
        if (maximum == 0 || travel <= 0) {
            scrollOffset = 0;
            return;
        }
        int thumbY = (int) Math.round(mouseY - scrollbarDragOffset);
        thumbY = Math.max(listTop, Math.min(listTop + travel, thumbY));
        scrollOffset = Math.max(0, Math.min(maximum,
                (int) Math.round((double) (thumbY - listTop) * maximum / travel)));
    }

    private void renderCompactContents(GuiGraphics graphics, List<RouteStatus.PlayerRoute> players,
            boolean hasSnapshot, boolean stale, String ownName, RouteStatus.PlayerRoute ownRoute,
            int mouseX, int mouseY) {
        int contentRight = Math.max(LEFT + 1, width - 18);
        int ownValueX = LEFT + 76;
        int ownValueWidth = Math.max(1, contentRight - ownValueX);

        drawCentered(graphics, title.getString(), width / 2, 12, 0xFFFFFFFF);
        String ownStatus = stale
                ? Component.translatable("mclink.status_disconnected").getString()
                : !hasSnapshot || ownRoute == null
                    ? Component.translatable("mclink.status_waiting").getString()
                    : routeLabel(ownRoute, false, McLinkClient.state().isHosting());
        int ownStatusColor = stale ? 0xFFFF5555 : !hasSnapshot || ownRoute == null ? 0xFFAAAAAA
                : routeColor(ownRoute.status(), false, McLinkClient.state().isHosting());
        String ownPing = stale || ownRoute == null
                ? Component.translatable("mclink.status_ping_unavailable").getString()
                : pingLabel(ownRoute.pingMillis(), false);
        drawText(graphics, Component.translatable("mclink.status_own").getString(), LEFT, 34, 68, 0xFFAAAAAA);
        drawText(graphics, ownStatus, ownValueX, 34, ownValueWidth, ownStatusColor);
        drawText(graphics, Component.translatable("mclink.status_ping_column").getString(), LEFT, 50, 68, 0xFFAAAAAA);
        drawText(graphics, ownPing, ownValueX, 50, ownValueWidth,
                stale || ownRoute == null || ownRoute.pingMillis() < 0 ? 0xFFAAAAAA : 0xFFDDDDDD);

        drawText(graphics, Component.translatable("mclink.status_player_column").getString(), LEFT, 76,
                Math.max(1, contentRight - LEFT), 0xFFAAAAAA);
        graphics.fill(LEFT, 90, contentRight, 91, 0x80555555);

        if (players.isEmpty()) {
            String empty = Component.translatable(stale ? "mclink.status_disconnected" : "mclink.status_waiting").getString();
            drawCentered(graphics, empty, width / 2,
                    listTop + Math.max(0, (listBottom - listTop) / 2 - 4), 0xFFDDDDDD);
            return;
        }

        clampScroll();
        int pingWidth = Math.min(92, Math.max(70, width / 3));
        int pingX = contentRight - pingWidth;
        int routeX = LEFT + 8;
        int routeWidth = Math.max(1, pingX - routeX - 8);
        int count = Math.min(visibleRows, players.size() - scrollOffset);
        for (int row = 0; row < count; row++) {
            RouteStatus.PlayerRoute player = players.get(scrollOffset + row);
            boolean self = player.playerName().equals(ownName);
            int y = listTop + row * rowHeight;
            String playerName = self
                    ? player.playerName() + " (" + Component.translatable("mclink.status_self").getString() + ")"
                    : player.playerName();
            boolean hostingSelf = self && McLinkClient.state().isHosting();
            drawText(graphics, playerName, LEFT, y + 1, contentRight - LEFT - 2,
                    self ? 0xFFFFFFFF : 0xFFDDDDDD);
            drawText(graphics, routeLabel(player, stale, hostingSelf), routeX, y + 17, routeWidth,
                    routeColor(player.status(), stale, hostingSelf));
            drawText(graphics, pingLabel(player.pingMillis(), stale), pingX, y + 17, pingWidth,
                    stale || player.pingMillis() < 0 ? 0xFFAAAAAA : 0xFFDDDDDD);
            graphics.fill(LEFT, y + rowHeight - 1, contentRight, y + rowHeight, 0x40333333);
        }
        drawScrollbar(graphics, players.size(), mouseX, mouseY);
    }

    private boolean scroll(int amount) {
        int maximum = Math.max(0, players().size() - visibleRows);
        int previous = scrollOffset;
        scrollOffset = (int) Math.max(0L, Math.min((long) maximum, (long) scrollOffset + amount));
        return previous != scrollOffset;
    }

    private void clampScroll() {
        scrollOffset = Math.min(scrollOffset, Math.max(0, players().size() - visibleRows));
    }

    private List<RouteStatus.PlayerRoute> players() {
        return Minecraft.getInstance().level == null ? List.of() : RouteStatusCache.players();
    }

    private String ownName() {
        var game = Minecraft.getInstance();
        return game.player == null ? "" : game.player.getName().getString();
    }

    private static RouteStatus.PlayerRoute findPlayer(List<RouteStatus.PlayerRoute> players, String name) {
        for (RouteStatus.PlayerRoute player : players) {
            if (player.playerName().equals(name)) return player;
        }
        return null;
    }

    private static String routeLabel(RouteStatus.PlayerRoute player, boolean stale, boolean hostingSelf) {
        if (stale) return Component.translatable("mclink.status_disconnected").getString();
        if (hostingSelf) return Component.translatable("mclink.status_host").getString();
        return Component.translatable(RouteStatus.translationKey(player.status())).getString();
    }

    private static int routeColor(int status, boolean stale, boolean hostingSelf) {
        if (stale) return 0xFFFF5555;
        if (hostingSelf) return 0xFFDDDDDD;
        return switch (status) {
            case RouteStatus.DIRECT -> 0xFF55FF55;
            case RouteStatus.RELAY -> 0xFFFFCC55;
            case RouteStatus.UNKNOWN -> 0xFFFFFF55;
            default -> 0xFFBBBBBB;
        };
    }

    private static String pingLabel(int pingMillis, boolean stale) {
        return stale || pingMillis < 0
                ? Component.translatable("mclink.status_ping_unavailable").getString()
                : pingMillis + " ms";
    }

    private void drawText(GuiGraphics graphics, String value, int x, int y, int maxWidth, int color) {
        graphics.drawString(font, fitText(value, maxWidth), x, y, color);
    }

    private void drawCentered(GuiGraphics graphics, String value, int x, int y, int color) {
        graphics.drawCenteredString(font, value, x, y, color);
    }

    private String fitText(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        String ellipsis = "…";
        int available = maxWidth - font.width(ellipsis);
        return available <= 0 ? "" : font.plainSubstrByWidth(value, available) + ellipsis;
    }
}

