package com.tailscale.mclink.mixin;

import com.tailscale.mclink.HostPlayerLimit;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMaxPlayersMixin {
    @Inject(method = "getMaxPlayerCount", at = @At("HEAD"), cancellable = true)
    private void mclink$useConfiguredHostLimit(CallbackInfoReturnable<Integer> callback) {
        int configured = HostPlayerLimit.activeFor(this);
        if (configured > 0) {
            callback.setReturnValue(configured);
        }
    }
}
