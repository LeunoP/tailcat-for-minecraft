package com.tailscale.mclink.mixin;

import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerManager.class)
public interface PlayerManagerAccessor {
    @Accessor("maxPlayers") int mclink$getMaxPlayers();

    @Mutable
    @Accessor("maxPlayers") void mclink$setMaxPlayers(int maxPlayers);
}
