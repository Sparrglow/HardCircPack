package com.sparrglow.hardcircpack.transport;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 硬件传输层接口，只负责字节收发，不解析业务帧、不查收发器注册表。
 * <p>
 * 生命周期归 {@link ServerTransportManager} 单例所有，收发器严禁持有本接口实例。
 */
public interface HardwareTransport {

    String getType();

    /**
     * 打开传输。阻塞调用，只允许在传输层专属线程执行。
     *
     * @return 是否成功
     */
    boolean open(Map<String, String> params);

    /**
     * 关闭传输，必须幂等，可由任意线程调用。
     */
    void close();

    /**
     * 提交待发送字节，只做原子替换写槽，永远非阻塞。
     */
    void send(byte[] data);

    /**
     * 非阻塞拉取读原子槽；无数据返回 null。只允许服务端全局 tick 调用。
     */
    byte[] pollReceived();

    boolean isConnected();

    TransportDescriptor getDescriptor();

    /**
     * 扫描可用端点。阻塞调用，只允许在传输层专属线程执行。
     */
    List<DiscoveryResult> discover();

    /**
     * 断开/失败回调，仅在「已连接 → 未连接」瞬间触发一次，参数为断开原因的语言键。
     * 回调线程不是游戏主线程，上层必须自行切回主线程。
     */
    void setDisconnectListener(Consumer<String> listener);
}
