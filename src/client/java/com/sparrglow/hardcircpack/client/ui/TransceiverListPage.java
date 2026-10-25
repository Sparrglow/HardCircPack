package com.sparrglow.hardcircpack.client.ui;

import com.sparrglow.hardcircpack.transceiver.TransceiverSummary;
import com.sparrglow.hardcircpack.transport.HubState;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 收发器列表页（只读）。点击条目仅向服务端请求详情，阶段1服务端回复占位提示。
 */
final class TransceiverListPage {

    private final ControlHubScreen screen;
    private final FlowLayout root;
    private final FlowLayout list;
    private final ScrollContainer<FlowLayout> scroll;

    TransceiverListPage(ControlHubScreen screen) {
        this.screen = screen;
        this.root = Containers.verticalFlow(Sizing.fill(100), Sizing.expand(100)).gap(UiTheme.GAP);

        FlowLayout header = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);
        LabelComponent title = Components.label(Component.translatable("screen.hardcirc_pack.transceivers.title"));
        title.color(UiTheme.TEXT_PRIMARY);
        title.shadow(false);
        title.sizing(Sizing.expand(100), Sizing.content());
        header.child(title);
        ButtonComponent refresh = Components.button(
                Component.translatable("screen.hardcirc_pack.action.refresh"),
                pressed -> this.screen.requestState());
        refresh.textShadow(false);
        header.child(refresh);
        this.root.child(header);

        this.list = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);
        this.scroll = Containers.verticalScroll(Sizing.fill(100), Sizing.expand(100), this.list);
        this.root.child(this.scroll);

        this.rebuild(List.of());
    }

    FlowLayout root() {
        return this.root;
    }

    void acceptState(HubState state) {
        this.rebuild(state.transceivers());
    }

    private void rebuild(List<TransceiverSummary> transceivers) {
        this.list.clearChildren();
        if (transceivers.isEmpty()) {
            LabelComponent empty = Components.label(Component.translatable("screen.hardcirc_pack.transceiver.empty"));
            empty.color(UiTheme.TEXT_MUTED);
            empty.shadow(false);
            this.list.child(empty);
            return;
        }

        for (TransceiverSummary summary : transceivers) {
            ButtonComponent entry = Components.button(
                    Component.translatable(summary.nameKey(), summary.id()),
                    pressed -> this.screen.sendTransceiverDetail(summary.id()));
            entry.textShadow(false);
            // 保持按钮默认的内容尺寸：fill(100) 会同时撑满宽高，导致每条收发器铺满整页。
            this.list.child(entry);
        }
    }
}
