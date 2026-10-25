package com.sparrglow.hardcircpack.transport;

import com.sparrglow.hardcircpack.HardCircPack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 传输类型注册表：类型 ID → 描述符 + 工厂。
 * 查表失败必须打日志，不得静默返回 null。
 */
public final class TransportRegistry {

    private record Entry(TransportDescriptor descriptor, Supplier<HardwareTransport> factory) {
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private TransportRegistry() {
    }

    public static void register(TransportDescriptor descriptor, Supplier<HardwareTransport> factory) {
        Entry previous = ENTRIES.putIfAbsent(descriptor.typeId(), new Entry(descriptor, factory));
        if (previous != null) {
            HardCircPack.LOGGER.warn("传输类型 {} 重复注册，已忽略后一次注册", descriptor.typeId());
        }
    }

    /**
     * @return 描述符；类型未注册时返回 null 并打日志
     */
    public static TransportDescriptor descriptor(String typeId) {
        Entry entry = ENTRIES.get(typeId);
        if (entry == null) {
            HardCircPack.LOGGER.warn("传输类型 {} 未注册", typeId);
            return null;
        }
        return entry.descriptor();
    }

    /**
     * @return 新传输实例；类型未注册时返回 null 并打日志
     */
    public static HardwareTransport create(String typeId) {
        Entry entry = ENTRIES.get(typeId);
        if (entry == null) {
            HardCircPack.LOGGER.warn("传输类型 {} 未注册，无法创建传输实例", typeId);
            return null;
        }
        return entry.factory().get();
    }

    /**
     * 所有已注册描述符，客户端据此动态生成配置页。
     */
    public static List<TransportDescriptor> descriptors() {
        List<TransportDescriptor> result = new ArrayList<>();
        for (Entry entry : ENTRIES.values()) {
            result.add(entry.descriptor());
        }
        result.sort((a, b) -> a.typeId().compareTo(b.typeId()));
        return List.copyOf(result);
    }
}
