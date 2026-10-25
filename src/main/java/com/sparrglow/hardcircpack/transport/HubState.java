package com.sparrglow.hardcircpack.transport;

import com.sparrglow.hardcircpack.transceiver.TransceiverSummary;

import java.util.List;

/**
 * 总控台一份完整的状态快照（连接状态 + 收发器列表 + 最近日志）。
 * <p>
 * {@code status} 是语言键而非文案，由客户端按游戏语言渲染；日志同理见 {@link LogEntry}。
 */
public record HubState(boolean connected, String typeId, String port, int baud, String status,
                       List<TransceiverSummary> transceivers, List<LogEntry> logs) {

    public static final String STATUS_IDLE = "screen.hardcirc_pack.status.idle";
    public static final String STATUS_CONNECTING = "screen.hardcirc_pack.status.connecting";
    public static final String STATUS_CONNECTED = "screen.hardcirc_pack.status.connected";
    public static final String STATUS_OPEN_FAILED = "screen.hardcirc_pack.status.open_failed";
    public static final String STATUS_DISCONNECTED = "screen.hardcirc_pack.status.disconnected";

    public HubState {
        typeId = typeId == null ? "" : typeId;
        port = port == null ? "" : port;
        status = status == null || status.isBlank() ? STATUS_IDLE : status;
        transceivers = List.copyOf(transceivers);
        logs = List.copyOf(logs);
    }

    public static HubState empty() {
        return new HubState(false, "", "", 0, STATUS_IDLE, List.of(), List.of());
    }
}
