package com.sparrglow.hardcircpack.network;

import com.sparrglow.hardcircpack.HardCircPack;
import com.sparrglow.hardcircpack.transceiver.TransceiverSummary;
import com.sparrglow.hardcircpack.transport.HubState;
import com.sparrglow.hardcircpack.transport.LogDirection;
import com.sparrglow.hardcircpack.transport.LogEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 总控台 C2S/S2C 载荷定义与编解码。
 * <p>
 * 客户端只上传「操作意图 + 参数」，所有校验与业务执行在服务端完成。
 */
public final class ControlHubPayloads {

    private static final StreamCodec<io.netty.buffer.ByteBuf, List<Integer>> INT_LIST =
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());
    private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> STRING_LIST =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());
    private static final StreamCodec<io.netty.buffer.ByteBuf, Map<String, String>> STRING_MAP =
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8);
    private static final StreamCodec<io.netty.buffer.ByteBuf, LogDirection> DIRECTION =
            StreamCodec.of(
                    (buffer, direction) -> ByteBufCodecs.STRING_UTF8.encode(buffer, direction.name()),
                    buffer -> LogDirection.valueOf(ByteBufCodecs.STRING_UTF8.decode(buffer)));
    private static final StreamCodec<io.netty.buffer.ByteBuf, LogEntry> LOG_ENTRY =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, LogEntry::time,
                    ByteBufCodecs.STRING_UTF8, LogEntry::level,
                    DIRECTION, LogEntry::direction,
                    ByteBufCodecs.STRING_UTF8, LogEntry::key,
                    STRING_LIST, LogEntry::args,
                    LogEntry::new);
    private static final StreamCodec<io.netty.buffer.ByteBuf, List<LogEntry>> LOG_LIST =
            LOG_ENTRY.apply(ByteBufCodecs.list());

    private ControlHubPayloads() {
    }

    // ------------------------------------------------------------------
    // C2S
    // ------------------------------------------------------------------

    /**
     * 请求打开传输：类型 ID + 描述符驱动的参数表。
     */
    public record ConnectPayload(String typeId, Map<String, String> params) implements CustomPacketPayload {

        public static final Type<ConnectPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "connect"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConnectPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ConnectPayload::typeId,
                        STRING_MAP, ConnectPayload::params,
                        ConnectPayload::new);

        @Override
        public Type<ConnectPayload> type() {
            return TYPE;
        }
    }

    /**
     * 请求断开当前传输。
     */
    public record DisconnectPayload() implements CustomPacketPayload {

        public static final Type<DisconnectPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "disconnect"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DisconnectPayload> STREAM_CODEC =
                StreamCodec.unit(new DisconnectPayload());

        @Override
        public Type<DisconnectPayload> type() {
            return TYPE;
        }
    }

    /**
     * 请求扫描端点。
     */
    public record DiscoverPayload(String typeId) implements CustomPacketPayload {

        public static final Type<DiscoverPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "discover"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DiscoverPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, DiscoverPayload::typeId,
                        DiscoverPayload::new);

        @Override
        public Type<DiscoverPayload> type() {
            return TYPE;
        }
    }

    /**
     * 请求收发器详情页（阶段2）。
     */
    public record OpenTransceiverDetailPayload(int transceiverId) implements CustomPacketPayload {

        public static final Type<OpenTransceiverDetailPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "open_transceiver_detail"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenTransceiverDetailPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenTransceiverDetailPayload::transceiverId,
                        OpenTransceiverDetailPayload::new);

        @Override
        public Type<OpenTransceiverDetailPayload> type() {
            return TYPE;
        }
    }

    /**
     * 请求刷新完整状态快照。
     */
    public record RequestStatePayload() implements CustomPacketPayload {

        public static final Type<RequestStatePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "request_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestStatePayload> STREAM_CODEC =
                StreamCodec.unit(new RequestStatePayload());

        @Override
        public Type<RequestStatePayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // S2C
    // ------------------------------------------------------------------

    /**
     * 完整状态快照：连接状态 + 收发器列表 + 最近 50 条日志。
     * <p>
     * {@code status} 与日志条目携带的都是语言键，客户端按自己的游戏语言渲染。
     */
    public record StatePayload(boolean connected, String typeId, String port, int baud, String status,
                               List<Integer> transceiverIds, List<String> transceiverNameKeys,
                               List<LogEntry> logs) implements CustomPacketPayload {

        public static final Type<StatePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, StatePayload::connected,
                        ByteBufCodecs.STRING_UTF8, StatePayload::typeId,
                        ByteBufCodecs.STRING_UTF8, StatePayload::port,
                        ByteBufCodecs.VAR_INT, StatePayload::baud,
                        ByteBufCodecs.STRING_UTF8, StatePayload::status,
                        INT_LIST, StatePayload::transceiverIds,
                        STRING_LIST, StatePayload::transceiverNameKeys,
                        LOG_LIST, StatePayload::logs,
                        StatePayload::new);

        public static StatePayload from(HubState state) {
            List<Integer> ids = new ArrayList<>();
            List<String> nameKeys = new ArrayList<>();
            for (TransceiverSummary summary : state.transceivers()) {
                ids.add(summary.id());
                nameKeys.add(summary.nameKey());
            }
            return new StatePayload(state.connected(), state.typeId(), state.port(), state.baud(),
                    state.status(), ids, nameKeys, state.logs());
        }

        public HubState toState() {
            List<TransceiverSummary> transceivers = new ArrayList<>();
            int count = Math.min(transceiverIds.size(), transceiverNameKeys.size());
            for (int i = 0; i < count; i++) {
                transceivers.add(new TransceiverSummary(transceiverIds.get(i), transceiverNameKeys.get(i)));
            }
            return new HubState(connected, typeId, port, baud, status, transceivers, logs);
        }

        @Override
        public Type<StatePayload> type() {
            return TYPE;
        }
    }

    /**
     * 端口扫描结果（值列表与展示名列表一一对应）。
     */
    public record PortsPayload(String typeId, List<String> values, List<String> labels)
            implements CustomPacketPayload {

        public static final Type<PortsPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "ports"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortsPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, PortsPayload::typeId,
                        STRING_LIST, PortsPayload::values,
                        STRING_LIST, PortsPayload::labels,
                        PortsPayload::new);

        @Override
        public Type<PortsPayload> type() {
            return TYPE;
        }
    }

    /**
     * 一次性提示（如收发器详情页占位），{@code key} 为语言键，由客户端本地化。
     */
    public record NoticePayload(String key) implements CustomPacketPayload {

        public static final Type<NoticePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(HardCircPack.MOD_ID, "notice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NoticePayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, NoticePayload::key,
                        NoticePayload::new);

        @Override
        public Type<NoticePayload> type() {
            return TYPE;
        }
    }
}
