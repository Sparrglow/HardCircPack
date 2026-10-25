package com.sparrglow.hardcircpack.transport;

import com.sparrglow.hardcircpack.HardCircPack;
import com.sparrglow.hardcircpack.transceiver.Transceiver;
import com.sparrglow.hardcircpack.transceiver.TransceiverRegistry;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 服务端传输路由层：全局唯一传输实例 + 全局 tick + 帧解析 + 按收发器 ID 分发。
 * <p>
 * 三层职责边界（强制）：
 * 传输层只收发字节；本类只解析并路由；收发器只收自己的端子值并跑逻辑/红石。
 * 所有状态字段与日志只允许在服务端主线程读写。
 */
public final class ServerTransportManager {

    private static final ServerTransportManager INSTANCE = new ServerTransportManager();
    private static final int MAX_LOG_ENTRIES = 50;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** 未识别数据按行聚合：换行、满额或静默超时后才整行输出，避免逐字节刷屏。 */
    private static final long RAW_LINE_IDLE_FLUSH_NANOS = 300_000_000L;
    private static final int RAW_LINE_MAX_BYTES = 512;
    /** 连接后持续收不到任何字节时，给出一条可操作的告警。 */
    private static final long NO_DATA_WARN_NANOS = 5_000_000_000L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HardCirc-Transport-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final List<HubStateListener> listeners = new CopyOnWriteArrayList<>();
    private final Deque<LogEntry> logs = new ArrayDeque<>();
    private final AtomicLong generation = new AtomicLong();

    private final ByteArrayOutputStream rawLine = new ByteArrayOutputStream();

    private long rawLineLastAppendNanos;
    private long connectedAtNanos;
    private boolean receivedAny;
    private boolean noDataWarned;

    private volatile HardwareTransport transport;
    private volatile MinecraftServer server;
    private volatile boolean connected;
    private volatile String typeId = "";
    private volatile String port = "";
    private volatile int baud;
    private volatile String status = HubState.STATUS_IDLE;

    private ServerTransportManager() {
    }

    public static ServerTransportManager instance() {
        return INSTANCE;
    }

    /**
     * 服务端启动：记录 server 实例，供控制线程切回主线程。
     */
    public void onServerStarted(MinecraftServer server) {
        this.server = server;
        log("INFO", "log.hardcirc_pack.server_started");
    }

    /**
     * 服务端停止：关闭传输、清空监听与日志。
     */
    public void onServerStopping() {
        generation.incrementAndGet();
        HardwareTransport current = this.transport;
        this.transport = null;
        if (current != null) {
            current.close();
        }
        connected = false;
        typeId = "";
        port = "";
        baud = 0;
        status = HubState.STATUS_IDLE;
        listeners.clear();
        logs.clear();
        TransceiverRegistry.clear();
        server = null;
    }

    /**
     * 全局 tick：每 tick 最多取 1 个原始包，解析后按收发器 ID 路由。
     * 收发器自己的 tick 严禁读取串口。
     */
    public void tick(MinecraftServer server) {
        HardwareTransport current = this.transport;
        if (current == null || !current.isConnected()) {
            return;
        }
        warnIfSilent();

        byte[] raw = current.pollReceived();
        if (raw == null) {
            flushRawLineIfIdle();
            return;
        }
        this.receivedAny = true;

        DataFrameCodec.Decoded frame = DataFrameCodec.decode(raw);
        if (frame == null) {
            appendRawBytes(raw);
            return;
        }

        log(LogDirection.RECEIVED, "INFO", "log.hardcirc_pack.rx_frame",
                String.valueOf(frame.transceiverId()), formatTerminals(frame.terminals()));

        Transceiver transceiver = TransceiverRegistry.get(frame.transceiverId());
        if (transceiver == null) {
            HardCircPack.LOGGER.warn("收发器ID {} 未注册，帧已丢弃", frame.transceiverId());
            log("WARN", "log.hardcirc_pack.transceiver_unregistered", String.valueOf(frame.transceiverId()));
            return;
        }
        transceiver.onDataReceived(frame.terminals());
    }

    /**
     * 打开传输。参数已由 ScreenHandler 转发，本方法负责服务端校验与异步 IO。
     */
    public void open(String typeId, Map<String, String> params) {
        TransportDescriptor descriptor = TransportRegistry.descriptor(typeId);
        if (descriptor == null) {
            log("WARN", "log.hardcirc_pack.unknown_transport", typeId);
            return;
        }
        if (!validate(descriptor, params)) {
            return;
        }

        long openGeneration = generation.incrementAndGet();
        closeCurrentTransport();

        String targetPort = params.getOrDefault("port", "");
        final int targetBaud = parseBaud(params);

        this.typeId = typeId;
        this.port = targetPort;
        this.baud = targetBaud;
        this.connected = false;
        this.status = HubState.STATUS_CONNECTING;
        log("INFO", "log.hardcirc_pack.opening", targetPort, String.valueOf(targetBaud));

        ioExecutor.execute(() -> {
            HardwareTransport created = TransportRegistry.create(typeId);
            boolean opened = created != null && created.open(params);
            MinecraftServer currentServer = this.server;
            if (currentServer == null) {
                if (created != null) {
                    created.close();
                }
                return;
            }
            currentServer.execute(() -> finishOpen(openGeneration, created, opened, targetPort, targetBaud));
        });
    }

    private static int parseBaud(Map<String, String> params) {
        try {
            return Integer.parseInt(params.getOrDefault("baud", "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 主动断开当前传输。
     */
    public void disconnect() {
        generation.incrementAndGet();
        closeCurrentTransport();
        connected = false;
        status = HubState.STATUS_IDLE;
        log("INFO", "log.hardcirc_pack.disconnected");
    }

    /**
     * 异步扫描端点，结果切回服务端主线程回调。
     */
    public void discover(String typeId, Consumer<List<DiscoveryResult>> callback) {
        if (TransportRegistry.descriptor(typeId) == null) {
            return;
        }
        ioExecutor.execute(() -> {
            HardwareTransport created = TransportRegistry.create(typeId);
            List<DiscoveryResult> results = created == null ? List.of() : created.discover();
            if (created != null) {
                created.close();
            }
            MinecraftServer currentServer = this.server;
            if (currentServer != null) {
                currentServer.execute(() -> callback.accept(results));
            }
        });
    }

    public void addListener(HubStateListener listener) {
        listeners.add(listener);
        listener.pushState(snapshot());
    }

    public void removeListener(HubStateListener listener) {
        listeners.remove(listener);
    }

    public void pushState(HubStateListener listener) {
        listener.pushState(snapshot());
    }

    public HubState snapshot() {
        return new HubState(connected, typeId, port, baud, status,
                TransceiverRegistry.summaries(), new ArrayList<>(logs));
    }

    public void log(String level, String key, String... args) {
        log(LogDirection.SYSTEM, level, key, args);
    }

    public void log(LogDirection direction, String level, String key, String... args) {
        logs.addLast(new LogEntry(LocalTime.now().format(TIME_FORMAT), level, direction, key, List.of(args)));
        while (logs.size() > MAX_LOG_ENTRIES) {
            logs.removeFirst();
        }
        if (direction == LogDirection.SYSTEM) {
            HardCircPack.LOGGER.info("[{}] {} {}", level, key, String.join(" ", args));
        } else {
            HardCircPack.LOGGER.debug("[{}] {} {}", direction, key, String.join(" ", args));
        }
        broadcast();
    }

    /** 端子值以逗号连接，作为一个翻译参数传入。 */
    private static String formatTerminals(int[] terminals) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < terminals.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(terminals[i]);
        }
        return builder.toString();
    }

    /**
     * 非协议帧数据按行聚合后原样输出：换行、满额或静默超时才输出一行，
     * 让控制台能看到设备真正打印了什么，而不是静默丢弃。
     */
    private void appendRawBytes(byte[] raw) {
        long now = System.nanoTime();
        for (byte value : raw) {
            if (value == '\n') {
                flushRawLine();
                continue;
            }
            if (value == '\r') {
                continue;
            }
            rawLine.write(value);
            rawLineLastAppendNanos = now;
            if (rawLine.size() >= RAW_LINE_MAX_BYTES) {
                flushRawLine();
            }
        }
    }

    /** 设备没有换行就停止输出时，把残留的半行也显示出来。 */
    private void flushRawLineIfIdle() {
        if (rawLine.size() > 0
                && System.nanoTime() - rawLineLastAppendNanos >= RAW_LINE_IDLE_FLUSH_NANOS) {
            flushRawLine();
        }
    }

    private void flushRawLine() {
        if (rawLine.size() == 0) {
            return;
        }
        byte[] data = rawLine.toByteArray();
        rawLine.reset();
        String decoded = new String(data, StandardCharsets.UTF_8);
        String text = decoded.strip();
        if (text.isEmpty()) {
            return;
        }
        log(LogDirection.RECEIVED, "INFO", "log.hardcirc_pack.rx_raw",
                isMostlyText(decoded) ? text : toHex(data));
    }

    /** UTF-8 解码后替换字符过多即视为二进制数据，改用十六进制显示。 */
    private static boolean isMostlyText(String decoded) {
        long replacement = decoded.chars().filter(ch -> ch == 0xFFFD).count();
        return replacement * 4 <= decoded.length();
    }

    /** 已连接却始终收不到字节时给出一次告警，避免界面看起来像卡死。 */
    private void warnIfSilent() {
        if (this.noDataWarned || this.receivedAny) {
            return;
        }
        if (System.nanoTime() - this.connectedAtNanos < NO_DATA_WARN_NANOS) {
            return;
        }
        this.noDataWarned = true;
        log("WARN", "log.hardcirc_pack.serial.no_data", this.port);
    }

    private void resetRawLine() {
        this.rawLine.reset();
        this.rawLineLastAppendNanos = System.nanoTime();
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (byte value : data) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", value));
        }
        return builder.toString();
    }

    private void finishOpen(long openGeneration, HardwareTransport created, boolean opened,
                            String targetPort, int targetBaud) {
        if (openGeneration != generation.get()) {
            if (created != null) {
                created.close();
            }
            return;
        }

        if (!opened || created == null) {
            if (created != null) {
                created.close();
            }
            this.connected = false;
            this.status = HubState.STATUS_OPEN_FAILED;
            log("ERROR", "log.hardcirc_pack.open_failed", targetPort);
            return;
        }

        HardwareTransport previous = this.transport;
        if (previous != null) {
            previous.close();
        }
        this.transport = created;
        created.setDisconnectListener(reason -> {
            MinecraftServer currentServer = this.server;
            if (currentServer != null) {
                currentServer.execute(() -> onTransportDisconnected(created, reason));
            }
        });

        this.connected = true;
        this.status = HubState.STATUS_CONNECTED;
        this.port = targetPort;
        this.baud = targetBaud;
        this.connectedAtNanos = System.nanoTime();
        this.receivedAny = false;
        this.noDataWarned = false;
        resetRawLine();
        log("INFO", "log.hardcirc_pack.opened", targetPort, String.valueOf(targetBaud));
    }

    private void onTransportDisconnected(HardwareTransport source, String reason) {
        if (this.transport != source) {
            return;
        }
        this.transport = null;
        this.connected = false;
        this.status = HubState.STATUS_DISCONNECTED;
        this.receivedAny = false;
        this.noDataWarned = false;
        resetRawLine();
        log("WARN", reason);
    }

    private void closeCurrentTransport() {
        HardwareTransport current = this.transport;
        this.transport = null;
        if (current != null) {
            ioExecutor.execute(current::close);
        }
    }

    private boolean validate(TransportDescriptor descriptor, Map<String, String> params) {
        for (ConfigField field : descriptor.fields()) {
            String value = params.get(field.key());
            if (field.required() && (value == null || value.isBlank())) {
                log("WARN", "log.hardcirc_pack.config.missing", field.key());
                return false;
            }
            if (value != null && field.type() == ConfigField.Type.NUMBER) {
                try {
                    Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    log("WARN", "log.hardcirc_pack.config.invalid_number", field.key(), value);
                    return false;
                }
            }
        }
        return true;
    }

    private void broadcast() {
        HubState state = snapshot();
        for (HubStateListener listener : listeners) {
            listener.pushState(state);
        }
    }
}
