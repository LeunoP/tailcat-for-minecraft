package com.tailscale.mclink;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class TailcatServerInitializer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(ServerShareHandler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerShareHandler::onServerStopping);
    }
}
