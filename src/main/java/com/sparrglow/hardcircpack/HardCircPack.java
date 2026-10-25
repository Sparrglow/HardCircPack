package com.sparrglow.hardcircpack;

import com.sparrglow.hardcircpack.network.ControlHubNetwork;
import com.sparrglow.hardcircpack.registry.ModContent;
import com.sparrglow.hardcircpack.transport.SerialTransport;
import com.sparrglow.hardcircpack.transport.ServerTransportManager;
import com.sparrglow.hardcircpack.transport.TransportRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HardCircPack implements ModInitializer {
	public static final String MOD_ID = "hardcirc_pack";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModContent.register();

		// 传输类型注册：阶段1只有串口，后续 WiFi/蓝牙在此追加
		TransportRegistry.register(SerialTransport.descriptor(), SerialTransport::new);

		ControlHubNetwork.registerPayloads();
		ControlHubNetwork.registerServerReceivers();

		// 服务端全局 tick：统一拉取串口数据并按收发器ID路由（收发器 tick 不得读串口）
		ServerTickEvents.END_SERVER_TICK.register(ServerTransportManager.instance()::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(ServerTransportManager.instance()::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				ServerTransportManager.instance().onServerStopping());
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
