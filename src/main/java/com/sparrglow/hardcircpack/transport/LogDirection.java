package com.sparrglow.hardcircpack.transport;

/**
 * 控制台日志方向：系统事件 / 串口接收 / 串口发送。
 * <p>
 * 方向只描述信息流向，与日志级别（INFO/WARN/ERROR）互相独立。
 */
public enum LogDirection {
    SYSTEM,
    RECEIVED,
    SENT
}
