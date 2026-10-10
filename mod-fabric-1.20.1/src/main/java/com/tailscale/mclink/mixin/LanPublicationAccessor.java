package com.tailscale.mclink.mixin;

import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.client.network.LanServerPinger;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(IntegratedServer.class)
public interface LanPublicationAccessor {
    @Accessor("lanPort") int mclink$getPublishedPort();
    @Accessor("lanPort") void mclink$setPublishedPort(int port);
    @Accessor("forcedGameMode") GameMode mclink$getPublishedGameType();
    @Accessor("forcedGameMode") void mclink$setPublishedGameType(GameMode type);
    @Accessor("lanPinger") LanServerPinger mclink$getPinger();
    @Accessor("lanPinger") void mclink$setPinger(LanServerPinger pinger);
}
