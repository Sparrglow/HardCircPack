package com.sparrglow.hardcircpack.transport;

import com.sparrglow.hardcircpack.HardCircPack;

/**
 * 阶段1固定 10 字节数据帧编解码。
 * <p>
 * 严格保持「单包输入 → 0 或 1 个解析结果」，不缓存、不重组、不跨包拆分。
 */
public final class DataFrameCodec {

    public static final int FRAME_LENGTH = 10;

    private static final byte HEADER_0 = (byte) 0xAA;
    private static final byte HEADER_1 = (byte) 0x55;
    private static final byte TYPE_DATA = (byte) 0x01;
    /** 方向语义（协议 4.4）：0x01=C2S=ESP32/硬件端→游戏端；0x02=S2C=游戏端→ESP32。 */
    private static final byte DIRECTION_C2S = (byte) 0x01;
    private static final byte DIRECTION_S2C = (byte) 0x02;

    private DataFrameCodec() {
    }

    /**
     * 解析结果。terminals 固定 4 个端子值，范围 0–15。
     */
    public record Decoded(int transceiverId, int[] terminals) {
    }

    /**
     * 按「帧头 → 校验和 → 帧类型 → 方向 → 收发器ID → 端子值」顺序校验，
     * 任一步失败整包丢弃并返回 null。
     */
    public static Decoded decode(byte[] raw) {
        if (raw == null || raw.length < FRAME_LENGTH) {
            HardCircPack.LOGGER.debug("串口包长度不足 {} 字节，整包丢弃", FRAME_LENGTH);
            return null;
        }
        if (raw[0] != HEADER_0 || raw[1] != HEADER_1) {
            HardCircPack.LOGGER.debug("串口包帧头不匹配，整包丢弃");
            return null;
        }

        byte checksum = 0;
        for (int i = 0; i < FRAME_LENGTH - 1; i++) {
            checksum += raw[i];
        }
        if (checksum != raw[FRAME_LENGTH - 1]) {
            HardCircPack.LOGGER.warn("串口包校验和失败，整包丢弃");
            return null;
        }
        if (raw[2] != TYPE_DATA) {
            HardCircPack.LOGGER.warn("串口包帧类型 {} 非法，整包丢弃", raw[2]);
            return null;
        }
        // 方向校验：decode 解析的是 ESP32 发往游戏端的 C2S 上行帧（协议 4.4 方向字节 0x01）。
        if (raw[3] != DIRECTION_C2S) {
            HardCircPack.LOGGER.warn("串口包方向 {} 非法（应为 C2S 0x01），整包丢弃", raw[3]);
            return null;
        }

        int transceiverId = raw[4] & 0xFF;
        if (transceiverId == 0) {
            HardCircPack.LOGGER.warn("串口包收发器ID为保留值 0，整包丢弃");
            return null;
        }

        int[] terminals = new int[4];
        for (int i = 0; i < terminals.length; i++) {
            int value = raw[5 + i] & 0xFF;
            if (value > 15) {
                HardCircPack.LOGGER.warn("串口包端子{}值 {} 超出 0–15，整包丢弃", i, value);
                return null;
            }
            terminals[i] = value;
        }
        return new Decoded(transceiverId, terminals);
    }

    /**
     * 打包游戏端下发帧（方向 S2C 0x02）。阶段2收发器数据模式输出使用。
     */
    public static byte[] encode(int transceiverId, int[] terminals) {
        byte[] frame = new byte[FRAME_LENGTH];
        frame[0] = HEADER_0;
        frame[1] = HEADER_1;
        frame[2] = TYPE_DATA;
        frame[3] = DIRECTION_S2C;
        frame[4] = (byte) (transceiverId & 0xFF);
        for (int i = 0; i < 4; i++) {
            int value = terminals != null && i < terminals.length ? terminals[i] : 0;
            frame[5 + i] = (byte) Math.max(0, Math.min(15, value));
        }

        byte checksum = 0;
        for (int i = 0; i < FRAME_LENGTH - 1; i++) {
            checksum += frame[i];
        }
        frame[FRAME_LENGTH - 1] = checksum;
        return frame;
    }
}
