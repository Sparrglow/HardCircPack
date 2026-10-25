package com.sparrglow.hardcircpack.transceiver;

import com.sparrglow.hardcircpack.HardCircPack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收发器注册表：收发器 ID → 实例，供路由层按帧内 ID 查表分发。
 * <p>
 * 注册/注销发生在服务端主线程，查表可能发生在任意线程，因此使用并发 Map。
 */
public final class TransceiverRegistry {

    private static final Map<Integer, Transceiver> TRANSCEIVERS = new ConcurrentHashMap<>();

    private TransceiverRegistry() {
    }

    /**
     * @return 注册成功返回 true；同 ID 已存在时拒绝并打日志
     */
    public static boolean register(Transceiver transceiver) {
        Transceiver previous = TRANSCEIVERS.putIfAbsent(transceiver.getTransceiverId(), transceiver);
        if (previous != null) {
            HardCircPack.LOGGER.warn("收发器ID {} 已注册，拒绝重复注册", transceiver.getTransceiverId());
            return false;
        }
        return true;
    }

    public static void unregister(int transceiverId) {
        if (TRANSCEIVERS.remove(transceiverId) == null) {
            HardCircPack.LOGGER.warn("收发器ID {} 未注册，注销被忽略", transceiverId);
        }
    }

    /**
     * @return 未注册时返回 null，由调用方（路由层）负责打日志
     */
    public static Transceiver get(int transceiverId) {
        return TRANSCEIVERS.get(transceiverId);
    }

    public static List<TransceiverSummary> summaries() {
        List<TransceiverSummary> result = new ArrayList<>();
        for (Transceiver transceiver : TRANSCEIVERS.values()) {
            result.add(new TransceiverSummary(transceiver.getTransceiverId(), transceiver.getDisplayNameKey()));
        }
        result.sort(Comparator.comparingInt(TransceiverSummary::id));
        return List.copyOf(result);
    }

    public static void clear() {
        TRANSCEIVERS.clear();
    }
}
