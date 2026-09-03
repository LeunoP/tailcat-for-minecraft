package com.tailscale.mclink;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@EventBusSubscriber(modid = "mclink")
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
