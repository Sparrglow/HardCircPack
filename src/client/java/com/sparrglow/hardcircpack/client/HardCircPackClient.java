package com.sparrglow.hardcircpack.client;

import com.sparrglow.hardcircpack.client.ui.ControlHubScreen;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.NoticePayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.PortsPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.StatePayload;
import com.sparrglow.hardcircpack.registry.ModContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * 客户端初始化：注册总控台 Screen 与 S2C 接收器。
 * <p>
 * 所有 owo-ui 类型只出现在本 sourceset。
 */
public class HardCircPackClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MenuScreens.register(ModContent.CONTROL_HUB_MENU, ControlHubScreen::new);

		ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> ControlHubClientState.acceptState(payload.toState())));

		ClientPlayNetworking.registerGlobalReceiver(PortsPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ControlHubClientState.acceptPorts(
						payload.typeId(), payload.values(), payload.labels())));

		ClientPlayNetworking.registerGlobalReceiver(NoticePayload.TYPE, (payload, context) ->
				context.client().execute(() -> ControlHubClientState.acceptNotice(payload.key())));
	}
}
