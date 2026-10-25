package com.sparrglow.hardcircpack.client.ui;

import com.sparrglow.hardcircpack.transport.HubState;
import com.sparrglow.hardcircpack.transport.LogDirection;
import com.sparrglow.hardcircpack.transport.LogEntry;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 控制台页：只读展示服务端最近 50 条收发日志。
 * <p>
 * 行格式 {@code [时间] [方向/级别] 本地化消息}；串口接收与发送使用不同颜色，系统事件为弱化色。
 */
final class ConsolePage {

    private final FlowLayout root;
    private final FlowLayout lines;
    private final ScrollContainer<FlowLayout> scroll;

    ConsolePage() {
        this.root = Containers.verticalFlow(Sizing.fill(100), Sizing.expand(100)).gap(UiTheme.GAP);

        LabelComponent title = Components.label(Component.translatable("screen.hardcirc_pack.console.title"));
        title.color(UiTheme.TEXT_PRIMARY);
        title.shadow(false);
        this.root.child(title);

        this.lines = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);
        this.scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(100), this.lines);
        this.root.child(this.scroll);

        this.rebuild(List.of());
    }

    FlowLayout root() {
        return this.root;
    }

    void acceptState(HubState state) {
        this.rebuild(state.logs());
    }

    private void rebuild(List<LogEntry> logs) {
        this.lines.clearChildren();
        if (logs.isEmpty()) {
            LabelComponent empty = Components.label(Component.translatable("screen.hardcirc_pack.console.empty"));
            empty.color(UiTheme.TEXT_MUTED);
            empty.shadow(false);
            this.lines.child(empty);
            return;
        }

        for (LogEntry log : logs) {
            LabelComponent entry = Components.label(render(log));
            entry.color(colorOf(log.direction()));
            entry.shadow(false);
            entry.maxWidth(UiTheme.LOG_MAX_WIDTH);
            entry.lineHeight(UiTheme.TEXT_LINE_HEIGHT);
            entry.lineSpacing(UiTheme.TEXT_LINE_SPACING);
            this.lines.child(entry);
        }
        this.scrollToBottom();
    }

    /**
     * 时间原样展示；串口收发用 RX/TX 标签并按方向着色，系统事件沿用级别标签。
     */
    private static Component render(LogEntry log) {
        String tag = switch (log.direction()) {
            case RECEIVED -> "RX";
            case SENT -> "TX";
            case SYSTEM -> log.level();
        };
        return Component.literal("[" + log.time() + "] [" + tag + "] ")
                .append(Component.translatable(log.key(), log.args().toArray()));
    }

    private static Color colorOf(LogDirection direction) {
        return switch (direction) {
            case RECEIVED -> UiTheme.LOG_RECEIVED;
            case SENT -> UiTheme.LOG_SENT;
            case SYSTEM -> UiTheme.TEXT_MUTED;
        };
    }

    /**
     * 排队滚动到底部。只有页面真正挂到组件树上才允许排队：
     * owo 的 queue 会沿父链向上委托，页面未挂载时父链为空，直接 NPE。
     */
    private void scrollToBottom() {
        if (!this.root.hasParent()) {
            return;
        }
        this.scroll.queue(() -> this.scroll.scrollTo(1.0));
    }

    /**
     * 页面被切换到前台后滚动到底部。
     */
    void onShown() {
        this.scrollToBottom();
    }
}
