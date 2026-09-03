package com.tailscale.mclink;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod("mclink")
public final class McLinkMod {
    public McLinkMod() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> McLinkClient::new);
    }
}
