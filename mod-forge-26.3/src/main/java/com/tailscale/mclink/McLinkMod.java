package com.tailscale.mclink;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("mclink")
public final class McLinkMod {
    public McLinkMod() {
        if (FMLEnvironment.dist.isClient()) {
            new McLinkClient();
        }
        RegisterCommandsEvent.BUS.addListener(this::onRegisterCommands);
        ServerStartedEvent.BUS.addListener(this::onServerStarted);
        ServerStoppedEvent.BUS.addListener(this::onServerStopped);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        TailcatCommands.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }

    private void onServerStarted(ServerStartedEvent event) {
        ServerShareHandler.onServerStarted(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        ServerShareHandler.onServerStopped(event.getServer());
    }
}
