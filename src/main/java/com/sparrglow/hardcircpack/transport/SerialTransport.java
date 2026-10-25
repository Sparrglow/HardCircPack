package com.sparrglow.hardcircpack.transport;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.sparrglow.hardcircpack.HardCircPack;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * 阶段1唯一传输实现：jSerialComm 串口。
 * <p>
 * 读写由两个独立线程驱动，所有串口 IO 与游戏主线程完全隔离；生命周期由
 * {@link ServerTransportManager} 单例管理。
 */
public final class SerialTransport implements HardwareTransport {

    public static final String TYPE_ID = "serial";

    /** 断开原因使用语言键，由客户端按自己的游戏语言渲染。 */
    private static final String FAULT_PORT_LOST = "log.hardcirc_pack.serial.port_lost";
    private static final String FAULT_READ_FAILED = "log.hardcirc_pack.serial.read_failed";
    private static final String FAULT_WRITE_FAILED = "log.hardcirc_pack.serial.write_failed";

    private static final int READ_TIMEOUT_MS = 30;
    private static final int WRITE_TIMEOUT_MS = 500;
    private static final int MAX_WRITE_BACKLOG_BYTES = 800;
    private static final int READ_BUFFER_SIZE = 1024;
    /** 读缓冲上限：消费端长时间不拉取时的兜底，正常 20 TPS 轮询远达不到。 */
    private static final int MAX_READ_BACKLOG_BYTES = 65536;
    private static final long WRITE_IDLE_PARK_NANOS = 2_000_000L;
    /** 无数据时的读取诊断心跳间隔（DEBUG 级，仅写日志文件）。 */
    private static final long IDLE_DIAG_LOG_NANOS = 5_000_000_000L;

    private final Object readBufferLock = new Object();
    private final AtomicReference<byte[]> writeSlot = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean failureNotified = new AtomicBoolean(false);

    /**
     * 读缓冲必须累积而不是单槽覆盖：读线程约每 30ms 采一次，服务端 tick 每 50ms 拉一次，
     * 单槽会让两次拉取之间的数据直接丢失，表现为设备文本被截断（只剩「ms」之类的尾巴）。
     */
    private final ByteArrayOutputStream readBuffer = new ByteArrayOutputStream();
    private volatile SerialPort port;
    private volatile boolean connected;
    private volatile Consumer<String> disconnectListener;

    private Thread readThread;
    private Thread writeThread;

    /**
     * 串口描述符：端口下拉由 discover 填充，波特率默认 115200。
     */
    public static TransportDescriptor descriptor() {
        return new TransportDescriptor(TYPE_ID, "transport.hardcirc_pack.serial", List.of(
                new ConfigField("port", "transport.hardcirc_pack.serial.port",
                        ConfigField.Type.DROPDOWN, "", true, true),
                new ConfigField("baud", "transport.hardcirc_pack.serial.baud",
                        ConfigField.Type.NUMBER, "115200", true, false)
        ));
    }

    @Override
    public String getType() {
        return TYPE_ID;
    }

    @Override
    public boolean open(Map<String, String> params) {
        if (closed.get()) {
            HardCircPack.LOGGER.warn("串口实例已关闭，拒绝重复打开");
            return false;
        }
        if (connected) {
            HardCircPack.LOGGER.warn("串口已处于连接状态，拒绝重复打开");
            return false;
        }

        String portName = params.get("port");
        if (portName == null || portName.isBlank()) {
            HardCircPack.LOGGER.warn("串口名称为空，无法打开");
            return false;
        }

        int baud;
        try {
            baud = Integer.parseInt(params.getOrDefault("baud", "115200").trim());
        } catch (NumberFormatException e) {
            HardCircPack.LOGGER.warn("波特率不是合法整数: {}", params.get("baud"));
            return false;
        }
        if (baud < 300 || baud > 2_000_000) {
            HardCircPack.LOGGER.warn("波特率超出范围: {}", baud);
            return false;
        }

        SerialPort candidate;
        try {
            candidate = SerialPort.getCommPort(portName);
        } catch (Exception e) {
            HardCircPack.LOGGER.warn("获取串口 {} 失败", portName, e);
            return false;
        }

        try {
            candidate.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            candidate.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                    READ_TIMEOUT_MS, WRITE_TIMEOUT_MS);
            if (!candidate.openPort()) {
                HardCircPack.LOGGER.warn("串口 {} 打开失败，错误码 {}", portName, candidate.getLastErrorCode());
                return false;
            }
            candidate.addDataListener(new DisconnectListener());
        } catch (Exception e) {
            HardCircPack.LOGGER.warn("串口 {} 初始化失败", portName, e);
            candidate.closePort();
            return false;
        }

        this.port = candidate;
        this.connected = true;
        this.failureNotified.set(false);
        startIoThreads();
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connected = false;
        failureNotified.set(true);

        SerialPort current = this.port;
        this.port = null;
        if (current != null) {
            try {
                current.closePort();
            } catch (Exception e) {
                HardCircPack.LOGGER.warn("关闭串口时发生异常", e);
            }
        }

        writeSlot.set(null);
        synchronized (readBufferLock) {
            readBuffer.reset();
        }
        if (readThread != null) {
            readThread.interrupt();
        }
        if (writeThread != null) {
            writeThread.interrupt();
        }
    }

    @Override
    public void send(byte[] data) {
        if (data == null || data.length == 0 || !connected) {
            return;
        }
        writeSlot.set(data);
    }

    @Override
    public byte[] pollReceived() {
        synchronized (readBufferLock) {
            if (readBuffer.size() == 0) {
                return null;
            }
            byte[] value = readBuffer.toByteArray();
            readBuffer.reset();
            return value;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public TransportDescriptor getDescriptor() {
        return descriptor();
    }

    @Override
    public List<DiscoveryResult> discover() {
        List<DiscoveryResult> results = new ArrayList<>();
        try {
            for (SerialPort candidate : SerialPort.getCommPorts()) {
                results.add(new DiscoveryResult(candidate.getSystemPortName(), candidate.getDescriptivePortName()));
            }
        } catch (Exception e) {
            HardCircPack.LOGGER.warn("扫描串口失败", e);
        }
        return results;
    }

    @Override
    public void setDisconnectListener(Consumer<String> listener) {
        this.disconnectListener = listener;
    }

    private void startIoThreads() {
        readThread = new Thread(this::readLoop, "HardCirc-Serial-Read");
        readThread.setDaemon(true);
        writeThread = new Thread(this::writeLoop, "HardCirc-Serial-Write");
        writeThread.setDaemon(true);
        readThread.start();
        writeThread.start();
    }

    /**
     * 读线程：只从串口读字节追加到读缓冲，不做任何解析。
     */
    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        long totalRead = 0;
        long lastIdleLogNanos = 0;
        while (connected) {
            SerialPort current = this.port;
            if (current == null) {
                break;
            }
            try {
                int read = current.readBytes(buffer, buffer.length);
                if (read < 0) {
                    HardCircPack.LOGGER.warn("串口读取返回 -1");
                    fail(FAULT_READ_FAILED);
                    break;
                }
                if (read == 0) {
                    long now = System.nanoTime();
                    if (now - lastIdleLogNanos >= IDLE_DIAG_LOG_NANOS) {
                        lastIdleLogNanos = now;
                        HardCircPack.LOGGER.debug("串口空闲诊断: 可读字节={}, 累计读取={}",
                                current.bytesAvailable(), totalRead);
                    }
                    continue;
                }
                totalRead += read;
                synchronized (readBufferLock) {
                    if (readBuffer.size() + read > MAX_READ_BACKLOG_BYTES) {
                        HardCircPack.LOGGER.warn("串口读缓冲超过 {} 字节，已丢弃旧数据",
                                MAX_READ_BACKLOG_BYTES);
                        readBuffer.reset();
                    }
                    readBuffer.write(buffer, 0, read);
                }
            } catch (Exception e) {
                HardCircPack.LOGGER.warn("串口读取异常", e);
                fail(FAULT_READ_FAILED);
                break;
            }
        }
    }

    /**
     * 写线程：只消费写槽调用 writeBytes，积压超阈值直接丢弃本帧。
     */
    private void writeLoop() {
        while (connected) {
            byte[] data = writeSlot.getAndSet(null);
            if (data == null) {
                LockSupport.parkNanos(WRITE_IDLE_PARK_NANOS);
                continue;
            }

            SerialPort current = this.port;
            if (current == null) {
                break;
            }
            try {
                if (current.bytesAwaitingWrite() > MAX_WRITE_BACKLOG_BYTES) {
                    HardCircPack.LOGGER.warn("串口写积压超过 {} 字节，丢弃本次数据", MAX_WRITE_BACKLOG_BYTES);
                    continue;
                }
                if (current.writeBytes(data, data.length) < 0) {
                    HardCircPack.LOGGER.warn("串口写入失败");
                    fail(FAULT_WRITE_FAILED);
                    break;
                }
            } catch (Exception e) {
                HardCircPack.LOGGER.warn("串口写入异常", e);
                fail(FAULT_WRITE_FAILED);
                break;
            }
        }
    }

    /**
     * 熔断：仅在「已连接 → 未连接」瞬间通知一次，回调可能运行在串口 IO 线程。
     *
     * @param reasonKey 断开原因的语言键，由上层原样转交客户端渲染，不得填入具体文案
     */
    private void fail(String reasonKey) {
        if (!failureNotified.compareAndSet(false, true)) {
            return;
        }
        connected = false;

        SerialPort current = this.port;
        this.port = null;
        if (current != null) {
            try {
                current.closePort();
            } catch (Exception e) {
                HardCircPack.LOGGER.warn("熔断时关闭串口异常", e);
            }
        }

        writeSlot.set(null);
        synchronized (readBufferLock) {
            readBuffer.reset();
        }

        HardCircPack.LOGGER.warn("串口断开: {}", reasonKey);
        Consumer<String> listener = this.disconnectListener;
        if (listener != null) {
            listener.accept(reasonKey);
        }
    }

    private final class DisconnectListener implements SerialPortDataListener {

        @Override
        public int getListeningEvents() {
            return SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
        }

        @Override
        public void serialEvent(SerialPortEvent event) {
            if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                fail(FAULT_PORT_LOST);
            }
        }
    }
}
