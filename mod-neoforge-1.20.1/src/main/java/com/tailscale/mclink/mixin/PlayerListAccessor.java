package com.tailscale.mclink.mixin;

import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("maxPlayers") int mclink$getMaxPlayers();
    @Mutable @Accessor("maxPlayers") void mclink$setMaxPlayers(int maxPlayers);
}
