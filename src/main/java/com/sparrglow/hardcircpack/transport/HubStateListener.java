package com.sparrglow.hardcircpack.transport;

/**
 * 状态推送接收方。总控台 ScreenHandler 实现本接口，由
 * {@link ServerTransportManager} 在服务端主线程调用。
 */
public interface HubStateListener {

    void pushState(HubState state);
}
