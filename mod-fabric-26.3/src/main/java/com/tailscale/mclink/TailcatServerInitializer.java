package com.tailscale.mclink;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class TailcatServerInitializer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(TailcatCommands::register);
        ServerLifecycleEvents.SERVER_STARTED.register(ServerShareHandler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(ServerShareHandler::onServerStopped);
    }
}
