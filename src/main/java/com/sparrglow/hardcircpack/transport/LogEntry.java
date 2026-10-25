package com.sparrglow.hardcircpack.transport;

import java.util.List;

/**
 * 一条结构化控制台日志：时间 + 级别 + 方向 + 语言键 + 参数。
 * <p>
 * 服务端只产生语言键与参数，由客户端按自己的游戏语言渲染，因此界面文案与服务端语言无关。
 */
public record LogEntry(String time, String level, LogDirection direction, String key, List<String> args) {

    public LogEntry {
        time = time == null ? "" : time;
        level = level == null ? "" : level;
        direction = direction == null ? LogDirection.SYSTEM : direction;
        key = key == null ? "" : key;
        args = List.copyOf(args);
    }
}
