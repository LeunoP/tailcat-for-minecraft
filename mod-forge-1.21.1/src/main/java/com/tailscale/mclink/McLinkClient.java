package com.tailscale.mclink;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod("mclink")
public final class McLinkClient {
    private static ScreenState state = new ScreenState();

    public McLinkClient() {
        this(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
    }

    public McLinkClient(IEventBus modEventBus) {
        if (modEventBus != null) {
            modEventBus.addListener(this::onClientSetup);
        }
    }

    public McLinkClient(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext context) {
        this(context != null ? context.getModEventBus() : null);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(McLinkEvents.class);
    }

    public static ScreenState state() {
        return state;
    }

    @Mod.EventBusSubscriber(modid = "mclink", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class McLinkEvents {

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            Screen screen = event.getScreen();
            Minecraft client = Minecraft.getInstance();
            if (screen instanceof PauseScreen && client.hasSingleplayerServer()) {
                addShareButton(client, screen, event);
            } else if (screen instanceof JoinMultiplayerScreen) {
                addConnectButton(client, screen, screen.width, screen.height, event);
            }
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != Phase.END) return;
            Minecraft client = Minecraft.getInstance();
            state.tick(client);
            String error = state.takeError();
            if (error != null) {
                SystemToast.add(
                    client.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal("Remote LAN connection failed"),
                    Component.literal(error)
                );
            }
        }

        @SubscribeEvent
        public static void onGameShuttingDown(net.minecraftforge.event.GameShuttingDownEvent event) {
            state.close();
        }
    }

    private static void addShareButton(Minecraft client, Screen screen, ScreenEvent.Init.Post event) {
        if (findButton(event, "mclink.share") != null) return;
        Button anchor = findButton(event, "menu.options");
        if (anchor == null) return;

        int newY = anchor.getY() + anchor.getHeight() + 4;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button b && b.getY() > anchor.getY()) {
                b.setY(b.getY() + 24);
            }
        }

        Button shareBtn = Button.builder(Component.translatable("mclink.share"),
                b -> client.setScreen(new ShareScreen(screen)))
                .bounds(screen.width / 2 - 102, newY, 204, 20)
                .build();
        event.addListener(shareBtn);
    }

    private static void addConnectButton(Minecraft client, Screen screen, int width, int height, ScreenEvent.Init.Post event) {
        if (findButton(event, "mclink.join") != null) return;
        Button direct = findButton(event, "selectServer.direct");
        if (direct == null) return;

        int row3Y = 0;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button b) {
                if (b.getY() > row3Y) row3Y = b.getY();
                b.setY(b.getY() - 24);
            }
        }

        for (GuiEventListener child : screen.children()) {
            if (child instanceof ServerSelectionList list) {
                list.updateSizeAndPosition(width, height - 120, 32);
                break;
            }
        }

        Button joinBtn = Button.builder(Component.translatable("mclink.join"),
                b -> client.setScreen(new JoinRemoteScreen(screen)))
                .bounds(width / 2 - 102, row3Y, 204, 20)
                .build();
        event.addListener(joinBtn);
    }

    private static Button findButton(ScreenEvent.Init.Post event, String translationKey) {
        String label = Component.translatable(translationKey).getString();
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button b && b.getMessage().getString().equals(label)) {
                return b;
            }
        }
        return null;
    }
}
