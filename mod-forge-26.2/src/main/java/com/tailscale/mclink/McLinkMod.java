package com.tailscale.mclink;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("mclink")
public final class McLinkMod {
    public McLinkMod() {
        if (FMLEnvironment.dist.isClient()) {
            new McLinkClient();
        }
    }
}
