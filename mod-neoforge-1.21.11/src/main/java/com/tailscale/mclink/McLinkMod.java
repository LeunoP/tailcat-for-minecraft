package com.tailscale.mclink;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;

@Mod("mclink")
public final class McLinkMod {
    public McLinkMod(IEventBus modEventBus) {
        modEventBus.addListener(RouteStatusPublisher::registerPayloads);
    }
}
