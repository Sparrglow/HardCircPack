package com.sparrglow.hardcircpack.client.ui;

import com.sparrglow.hardcircpack.client.ControlHubClientState;
import com.sparrglow.hardcircpack.transport.ConfigField;
import com.sparrglow.hardcircpack.transport.DiscoveryResult;
import com.sparrglow.hardcircpack.transport.HubState;
import com.sparrglow.hardcircpack.transport.TransportDescriptor;
import com.sparrglow.hardcircpack.transport.TransportRegistry;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.ParentComponent;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接管理页：为每个已注册传输类型按 {@link TransportDescriptor} 生成一个配置面板。
 * <p>
 * 页面内不存在任何具体传输方式的字段硬编码，新增 WiFi/蓝牙时无需改动本类。
 */
final class ConnectionPage {

    private final ControlHubScreen screen;
    private final FlowLayout root;
    private final FlowLayout list;
    private final List<Section> sections = new ArrayList<>();

    ConnectionPage(ControlHubScreen screen) {
        this.screen = screen;
        this.list = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);
        this.root = Containers.verticalFlow(Sizing.fill(100), Sizing.expand(100));

        List<TransportDescriptor> descriptors = TransportRegistry.descriptors();
        if (descriptors.isEmpty()) {
            LabelComponent empty = Components.label(Component.translatable("screen.hardcirc_pack.no_transport"));
            empty.color(UiTheme.TEXT_MUTED);
            empty.shadow(false);
            this.list.child(empty);
        } else {
            for (TransportDescriptor descriptor : descriptors) {
                Section section = new Section(this.screen, descriptor);
                this.sections.add(section);
                this.list.child(section.root());
            }
        }

        this.root.child(Containers.verticalScroll(Sizing.fill(100), Sizing.expand(100), this.list));
    }

    FlowLayout root() {
        return this.root;
    }

    void requestDiscoveries() {
        for (Section section : this.sections) {
            section.requestDiscovery();
        }
    }

    void acceptState(HubState state) {
        for (Section section : this.sections) {
            section.acceptState(state);
        }
    }

    void acceptPorts(String typeId, List<DiscoveryResult> results) {
        for (Section section : this.sections) {
            section.acceptPorts(typeId, results);
        }
    }

    /**
     * 单个传输类型的配置面板。
     */
    private static final class Section {

        private final ControlHubScreen screen;
        private final TransportDescriptor descriptor;
        private final FlowLayout root;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, ButtonComponent> dropdownPickers = new LinkedHashMap<>();
        private final List<DiscoveryResult> discovered = new ArrayList<>();
        private final LabelComponent statusLabel;
        private final ButtonComponent connectButton;
        private final ButtonComponent disconnectButton;

        Section(ControlHubScreen screen, TransportDescriptor descriptor) {
            this.screen = screen;
            this.descriptor = descriptor;

            this.root = Containers.verticalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);
            this.root.surface(Surface.PANEL);
            this.root.padding(Insets.of(UiTheme.PANEL_PADDING));

            LabelComponent title = Components.label(Component.translatable(descriptor.displayKey()));
            title.color(UiTheme.TEXT_PRIMARY);
            title.shadow(false);
            this.root.child(title);

            for (ConfigField field : descriptor.fields()) {
                String remembered = ControlHubClientState.rememberedConfig(descriptor.typeId(), field.key());
                this.values.put(field.key(), remembered != null ? remembered : field.defaultValue());
                this.root.child(this.buildFieldRow(field));
            }

            FlowLayout actions = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);
            this.connectButton = Components.button(
                    Component.translatable("screen.hardcirc_pack.action.connect"),
                    pressed -> this.screen.sendConnect(this.descriptor.typeId(), this.values));
            this.connectButton.textShadow(false);
            this.disconnectButton = Components.button(
                    Component.translatable("screen.hardcirc_pack.action.disconnect"),
                    pressed -> this.screen.sendDisconnect());
            this.disconnectButton.textShadow(false);
            ButtonComponent discoverButton = Components.button(
                    Component.translatable("screen.hardcirc_pack.action.discover"),
                    pressed -> this.requestDiscovery());
            discoverButton.textShadow(false);
            actions.child(this.connectButton);
            actions.child(this.disconnectButton);
            actions.child(discoverButton);
            this.root.child(actions);

            this.statusLabel = Components.label(Component.translatable("screen.hardcirc_pack.status.idle"));
            this.statusLabel.color(UiTheme.TEXT_MUTED);
            this.statusLabel.shadow(false);
            this.root.child(this.statusLabel);

            this.disconnectButton.active(false);
        }

        FlowLayout root() {
            return this.root;
        }

        private FlowLayout buildFieldRow(ConfigField field) {
            FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);

            LabelComponent label = Components.label(Component.translatable(field.labelKey()));
            label.color(UiTheme.TEXT_MUTED);
            label.shadow(false);
            row.child(label);

            switch (field.type()) {
                case DROPDOWN -> {
                    String current = this.values.getOrDefault(field.key(), "");
                    ButtonComponent picker = Components.button(
                            Component.literal(current.isBlank() ? "—" : current),
                            pressed -> this.openPicker(pressed, field));
                    picker.textShadow(false);
                    this.dropdownPickers.put(field.key(), picker);
                    row.child(picker);
                }
                case NUMBER, TEXT -> {
                    TextBoxComponent box = Components.textBox(
                            Sizing.fixed(UiTheme.INPUT_WIDTH), this.values.getOrDefault(field.key(), ""));
                    box.onChanged().subscribe(value -> {
                        this.values.put(field.key(), value);
                        ControlHubClientState.rememberConfig(this.descriptor.typeId(), field.key(), value);
                    });
                    row.child(box);
                }
            }

            return row;
        }

        /**
         * 更新下拉按钮显示的当前值。
         * <p>
         * vanilla 的 {@code setMessage} 只改文本，不会通知 owo 重新布局；按钮宽度会停留在旧文本长度，
         * 表现为底色比文字短、点击热区也对不上。这里显式通知父组件重算内容尺寸。
         */
        private void setPickerLabel(ButtonComponent picker, String label) {
            picker.setMessage(Component.literal(label));
            ParentComponent parent = picker.parent();
            if (parent != null) {
                parent.onChildMutated(picker);
            }
        }

        private void openPicker(ButtonComponent anchor, ConfigField field) {
            this.screen.openDropdown(anchor.x(), anchor.y() + anchor.height(), dropdown -> {
                if (this.discovered.isEmpty()) {
                    dropdown.text(Component.translatable("screen.hardcirc_pack.no_ports"));
                    return;
                }
                for (DiscoveryResult result : this.discovered) {
                    dropdown.button(Component.literal(result.label()), clicked -> {
                        this.values.put(field.key(), result.value());
                        ControlHubClientState.rememberConfig(
                                this.descriptor.typeId(), field.key(), result.value());
                        this.setPickerLabel(anchor, result.label());
                        this.screen.closeDropdown(clicked);
                    });
                }
            });
        }

        void requestDiscovery() {
            this.screen.sendDiscover(this.descriptor.typeId());
        }

        void acceptState(HubState state) {
            boolean isCurrentType = state.typeId().equals(this.descriptor.typeId());
            boolean active = state.connected() && isCurrentType;

            this.connectButton.active(!active);
            this.disconnectButton.active(active);
            this.statusLabel.text(active
                    ? Component.translatable(state.status())
                    : Component.translatable(HubState.STATUS_IDLE));
        }

        void acceptPorts(String typeId, List<DiscoveryResult> results) {
            if (!typeId.equals(this.descriptor.typeId())) {
                return;
            }

            this.discovered.clear();
            this.discovered.addAll(results);

            if (results.isEmpty()) {
                this.statusLabel.text(Component.translatable("screen.hardcirc_pack.no_ports"));
                return;
            }

            for (ConfigField field : this.descriptor.fields()) {
                if (!field.discoveryDriven()) {
                    continue;
                }
                String current = this.values.get(field.key());
                ButtonComponent picker = this.dropdownPickers.get(field.key());
                if (current != null && !current.isBlank()) {
                    // 恢复的记忆值仍在扫描结果里时，用完整标签刷新按钮文字。
                    for (DiscoveryResult result : results) {
                        if (result.value().equals(current)) {
                            if (picker != null) {
                                this.setPickerLabel(picker, result.label());
                            }
                            break;
                        }
                    }
                    continue;
                }
                DiscoveryResult first = results.get(0);
                this.values.put(field.key(), first.value());
                if (picker != null) {
                    this.setPickerLabel(picker, first.label());
                }
            }
        }
    }
}
