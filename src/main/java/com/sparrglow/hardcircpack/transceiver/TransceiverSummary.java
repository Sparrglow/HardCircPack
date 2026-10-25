package com.sparrglow.hardcircpack.transceiver;

/**
 * 收发器列表页的只读展示项。
 *
 * @param nameKey 展示名的语言键，客户端渲染时传入收发器 ID 作为参数
 */
public record TransceiverSummary(int id, String nameKey) {
}
