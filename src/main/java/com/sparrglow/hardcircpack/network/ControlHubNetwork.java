package com.sparrglow.hardcircpack.network;

import com.sparrglow.hardcircpack.HardCircPack;
import com.sparrglow.hardcircpack.menu.ControlHubScreenHandler;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.ConnectPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.DisconnectPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.DiscoverPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.NoticePayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.OpenTransceiverDetailPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.PortsPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.RequestStatePayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.StatePayload;
import com.sparrglow.hardcircpack.transport.DiscoveryResult;
import com.sparrglow.hardcircpack.transport.ServerTransportManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 总控台网络注册与服务端接收器。
 * <p>
 * 所有 C2S 请求都先校验「玩家当前确实打开了总控台菜单」，再交给
 * {@link ServerTransportManager} 执行。
 */
public final class ControlHubNetwork {

    private ControlHubNetwork() {
    }

    /**
     * 载荷类型必须在双端一致注册，客户端出发送前完成。
     */
    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(ConnectPayload.TYPE, ConnectPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DisconnectPayload.TYPE, DisconnectPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(DiscoverPayload.TYPE, DiscoverPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(OpenTransceiverDetailPayload.TYPE,
                OpenTransceiverDetailPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RequestStatePayload.TYPE, RequestStatePayload.STREAM_CODEC);

        PayloadTypeRegistry.playS2C().register(StatePayload.TYPE, StatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PortsPayload.TYPE, PortsPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(NoticePayload.TYPE, NoticePayload.STREAM_CODEC);
    }

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(ConnectPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (requireHubMenu(player, "连接") == null) {
                        return;
                    }
                    ServerTransportManager.instance().open(payload.typeId(), payload.params());
                }));

        ServerPlayNetworking.registerGlobalReceiver(DisconnectPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    if (requireHubMenu(context.player(), "断开") == null) {
                        return;
                    }
                    ServerTransportManager.instance().disconnect();
                }));

        ServerPlayNetworking.registerGlobalReceiver(DiscoverPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (requireHubMenu(player, "扫描端口") == null) {
                        return;
                    }
                    ServerTransportManager.instance().discover(payload.typeId(), results ->
                            sendPorts(player, payload.typeId(), results));
                }));

        ServerPlayNetworking.registerGlobalReceiver(OpenTransceiverDetailPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (requireHubMenu(player, "打开收发器详情") == null) {
                        return;
                    }
                    ServerPlayNetworking.send(player, new NoticePayload("screen.hardcirc_pack.notice.phase2"));
                }));

        ServerPlayNetworking.registerGlobalReceiver(RequestStatePayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    ControlHubScreenHandler menu = requireHubMenu(player, "刷新状态");
                    if (menu == null) {
                        return;
                    }
                    ServerTransportManager.instance().pushState(menu);
                }));
    }

    private static void sendPorts(ServerPlayer player, String typeId, List<DiscoveryResult> results) {
        if (player.hasDisconnected()) {
            return;
        }
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (DiscoveryResult result : results) {
            values.add(result.value());
            labels.add(result.label());
        }
        ServerPlayNetworking.send(player, new PortsPayload(typeId, values, labels));
    }

    private static ControlHubScreenHandler requireHubMenu(ServerPlayer player, String action) {
        if (player.containerMenu instanceof ControlHubScreenHandler menu && menu.stillValid(player)) {
            return menu;
        }
        HardCircPack.LOGGER.warn("玩家 {} 当前未打开总控台，忽略 {} 请求",
                player.getName().getString(), action);
        return null;
    }
}
