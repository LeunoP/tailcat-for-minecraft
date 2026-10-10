package com.tailscale.mclink.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(IntegratedServer.class)
public interface LanPublicationAccessor {
    @Accessor("publishedPort") int mclink$getPublishedPort();
    @Accessor("publishedPort") void mclink$setPublishedPort(int port);
    @Accessor("publishedGameType") GameType mclink$getPublishedGameType();
    @Accessor("publishedGameType") void mclink$setPublishedGameType(GameType type);
    @Accessor("lanPinger") LanServerPinger mclink$getPinger();
    @Accessor("lanPinger") void mclink$setPinger(LanServerPinger pinger);
}
