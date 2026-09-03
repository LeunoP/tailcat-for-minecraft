package com.tailscale.mclink;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "mclink", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TailcatServerEvents {
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ServerShareHandler.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerShareHandler.onServerStopping(event.getServer());
    }
}
