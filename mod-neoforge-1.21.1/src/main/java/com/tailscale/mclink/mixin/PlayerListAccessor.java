package com.tailscale.mclink.mixin;

import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PlayerList.class, remap = false)
public interface PlayerListAccessor {
    @Accessor(value = "maxPlayers", remap = false) int mclink$getMaxPlayers();
    @Mutable @Accessor(value = "maxPlayers", remap = false) void mclink$setMaxPlayers(int maxPlayers);
}
