package com.sparrglow.hardcircpack.client;

import com.sparrglow.hardcircpack.client.ui.ControlHubScreen;
import com.sparrglow.hardcircpack.transport.DiscoveryResult;
import com.sparrglow.hardcircpack.transport.HubState;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端状态缓存：S2C 载荷先落到这里，再转发给当前打开的总控台。
 * 只在客户端主线程读写。
 */
public final class ControlHubClientState {

    private static volatile HubState state = HubState.empty();
    /** 记住上次填写/选择的配置项，避免每次重开总控台都回到描述符默认值。 */
    private static final Map<String, String> rememberedConfig = new HashMap<>();

    private ControlHubClientState() {
    }

    /**
     * @return 上次记住的值；没有记录时返回 null
     */
    public static String rememberedConfig(String typeId, String fieldKey) {
        return rememberedConfig.get(typeId + '\u0000' + fieldKey);
    }

    public static void rememberConfig(String typeId, String fieldKey, String value) {
        rememberedConfig.put(typeId + '\u0000' + fieldKey, value);
    }

    public static HubState state() {
        return state;
    }

    public static void acceptState(HubState newState) {
        state = newState;
        ControlHubScreen screen = activeScreen();
        if (screen != null) {
            screen.onState(newState);
        }
    }

    public static void acceptPorts(String typeId, List<String> values, List<String> labels) {
        List<DiscoveryResult> results = new ArrayList<>();
        int count = Math.min(values.size(), labels.size());
        for (int i = 0; i < count; i++) {
            results.add(new DiscoveryResult(values.get(i), labels.get(i)));
        }
        ControlHubScreen screen = activeScreen();
        if (screen != null) {
            screen.onPorts(typeId, results);
        }
    }

    public static void acceptNotice(String key) {
        ControlHubScreen screen = activeScreen();
        if (screen != null) {
            screen.onNotice(key);
        }
    }

    private static ControlHubScreen activeScreen() {
        return Minecraft.getInstance().screen instanceof ControlHubScreen screen ? screen : null;
    }
}
