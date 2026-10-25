package com.sparrglow.hardcircpack.transceiver;

/**
 * 收发器层接口。收发器只负责接收属于自己的端子数据、跑收发器函数、更新红石；
 * 严禁持有或管理 {@code HardwareTransport}。
 * <p>
 * 阶段1不实现收发器方块，本接口与 {@link TransceiverRegistry} 仅为路由层提供落点。
 */
public interface Transceiver {

    /**
     * 协议帧中的 1 字节收发器 ID（1–255）。
     */
    int getTransceiverId();

    /**
     * 展示名的语言键，由客户端按当前游戏语言渲染，{@code %s} 为收发器 ID。
     */
    default String getDisplayNameKey() {
        return "transceiver.hardcirc_pack.name";
    }

    /**
     * 由 {@code ServerTransportManager} 在服务端主线程投递本收发器的端子值。
     * 实现方只做数据搬运，不得在此方法内执行红石更新。
     */
    void onDataReceived(int[] terminalValues);

    /**
     * 由 BlockEntityTicker 驱动：只跑收发器函数与红石更新。
     */
    void tick();

    void destroy();
}
