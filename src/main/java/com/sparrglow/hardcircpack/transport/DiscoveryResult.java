package com.sparrglow.hardcircpack.transport;

/**
 * 一次端口扫描的结果项。
 *
 * @param value 实际用于连接的标识（如 COM3）
 * @param label 展示用名称（如 COM3 - USB-SERIAL CH340）
 */
public record DiscoveryResult(String value, String label) {
}
