package com.nishiyu.enderframelib;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(EnderFrameLib.MODID)
public class EnderFrameLib {
    public static final String MODID = "enderframelib";
    public static final Logger LOGGER = LoggerFactory.getLogger(EnderFrameLib.class);

    public EnderFrameLib(IEventBus modEventBus) {
        LOGGER.info("EnderFrameLib Initialized.");
    }
}