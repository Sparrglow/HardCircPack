package com.sparrglow.hardcircpack.transport;

/**
 * 传输方式的一个配置字段。
 * <p>
 * 客户端 UI 只按 {@link Type} 生成控件，不得硬编码任何具体传输方式的字段。
 *
 * @param key             写回连接参数 Map 的键
 * @param labelKey        语言文件键
 * @param type            控件类型
 * @param defaultValue    默认值（连接时会写入参数 Map）
 * @param required        是否必填，服务端连接前校验
 * @param discoveryDriven 是否由 discover() 结果填充下拉框
 */
public record ConfigField(String key, String labelKey, Type type, String defaultValue,
                          boolean required, boolean discoveryDriven) {

    public enum Type {
        TEXT,
        NUMBER,
        DROPDOWN
    }
}
