package com.sparrglow.hardcircpack.transport;

import java.util.List;

/**
 * 传输方式的静态描述符，是「配置控件由描述符驱动生成」的唯一数据源。
 * <p>
 * 新增传输方式（WiFi/蓝牙）时只注册新的描述符，客户端 UI 代码零修改。
 *
 * @param typeId     传输类型 ID，例如 {@code serial}
 * @param displayKey 语言文件键
 * @param fields     配置字段清单
 */
public record TransportDescriptor(String typeId, String displayKey, List<ConfigField> fields) {

    public TransportDescriptor {
        fields = List.copyOf(fields);
    }
}
