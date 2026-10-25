package com.sparrglow.hardcircpack.client.ui;

import io.wispforest.owo.ui.core.Color;

/**
 * 全模组 UI 统一配色与尺寸常量。禁止在页面里硬编码颜色。
 */
public final class UiTheme {

    public static final Color TEXT_PRIMARY = Color.ofArgb(0xFFE6EAF0);
    public static final Color TEXT_MUTED = Color.ofArgb(0xFF9AA4B2);
    public static final Color ACCENT = Color.ofArgb(0xFF4C8DFF);

    /** 控制台日志方向配色：接收为低饱和青绿，发送为强调蓝。 */
    public static final Color LOG_RECEIVED = Color.ofArgb(0xFF86C7A6);
    public static final Color LOG_SENT = ACCENT;

    public static final int GAP = 4;
    public static final int PADDING = 8;
    public static final int PANEL_PADDING = 6;
    public static final int TEXT_LINE_HEIGHT = 9;
    public static final int TEXT_LINE_SPACING = 2;
    public static final int INPUT_WIDTH = 90;
    public static final int LOG_MAX_WIDTH = 400;

    private UiTheme() {
    }
}
