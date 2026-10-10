package com.tailscale.mclink.mixin;

import net.minecraft.server.network.ServerConnectionListener;
import io.netty.channel.ChannelFuture;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerConnectionListener.class)
public interface LanListenersAccessor {
    @Accessor("channels") List<ChannelFuture> mclink$getListeners();
}
