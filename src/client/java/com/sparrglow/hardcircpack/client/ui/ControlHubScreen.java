package com.sparrglow.hardcircpack.client.ui;

import com.sparrglow.hardcircpack.client.ControlHubClientState;
import com.sparrglow.hardcircpack.menu.ControlHubScreenHandler;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.ConnectPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.DisconnectPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.DiscoverPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.OpenTransceiverDetailPayload;
import com.sparrglow.hardcircpack.network.ControlHubPayloads.RequestStatePayload;
import com.sparrglow.hardcircpack.transport.DiscoveryResult;
import com.sparrglow.hardcircpack.transport.HubState;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.DropdownComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 总控台客户端界面：连接管理 / 收发器列表 / 控制台 三页。
 * <p>
 * 只负责渲染与收集输入，任何连接、断开、配置修改都通过 C2S 载荷交给服务端。
 */
public class ControlHubScreen extends BaseOwoScreen<FlowLayout> implements MenuAccess<ControlHubScreenHandler> {

    private static final int TAB_CONNECTION = 0;
    private static final int TAB_TRANSCEIVERS = 1;
    private static final int TAB_CONSOLE = 2;

    /** 弹层挂载在 root 上，层级远浅于页面内容，必须抬高 z 才不会被子组件遮挡。 */
    private static final int OVERLAY_Z_INDEX = 100;

    private final ControlHubScreenHandler handler;
    private final ConnectionPage connectionPage = new ConnectionPage(this);
    private final TransceiverListPage transceiverListPage = new TransceiverListPage(this);
    private final ConsolePage consolePage = new ConsolePage();

    private FlowLayout content;
    private LabelComponent statusLabel;
    private LabelComponent noticeLabel;
    private int currentTab = -1;
    private boolean stateRequested;

    public ControlHubScreen(ControlHubScreenHandler handler, Inventory inventory, Component title) {
        super(title);
        this.handler = handler;
    }

    @Override
    public ControlHubScreenHandler getMenu() {
        return this.handler;
    }

    /**
     * 总控台是容器式界面，不能暂停集成服务器。
     * <p>
     * Screen 默认返回 true，一旦暂停，ServerTickEvents 停止触发，
     * ServerTransportManager 的全局 tick 不再拉取串口数据，控制台永远收不到 RX。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.VANILLA_TRANSLUCENT);
        root.padding(Insets.of(UiTheme.PADDING));
        root.gap(UiTheme.GAP);

        FlowLayout header = Containers.horizontalFlow(Sizing.fill(100), Sizing.content())
                .gap(UiTheme.GAP);
        LabelComponent title = Components.label(Component.translatable("screen.hardcirc_pack.control_hub"));
        title.color(UiTheme.TEXT_PRIMARY);
        title.shadow(false);
        title.sizing(Sizing.expand(100), Sizing.content());
        header.child(title);
        this.statusLabel = Components.label(Component.translatable(ControlHubClientState.state().status()));
        this.statusLabel.color(UiTheme.TEXT_MUTED);
        this.statusLabel.shadow(false);
        header.child(this.statusLabel);
        root.child(header);

        FlowLayout tabRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content()).gap(UiTheme.GAP);
        tabRow.child(this.createTab("screen.hardcirc_pack.tab.connection", TAB_CONNECTION));
        tabRow.child(this.createTab("screen.hardcirc_pack.tab.transceivers", TAB_TRANSCEIVERS));
        tabRow.child(this.createTab("screen.hardcirc_pack.tab.console", TAB_CONSOLE));
        root.child(tabRow);

        this.content = Containers.verticalFlow(Sizing.fill(100), Sizing.expand(100));
        root.child(this.content);

        this.noticeLabel = Components.label(Component.empty());
        this.noticeLabel.color(UiTheme.ACCENT);
        this.noticeLabel.shadow(false);
        root.child(this.noticeLabel);

        this.showTab(TAB_CONNECTION);
    }

    @Override
    protected void init() {
        super.init();
        if (!this.stateRequested) {
            this.stateRequested = true;
            this.requestState();
            this.connectionPage.requestDiscoveries();
        }
        this.onState(ControlHubClientState.state());
    }

    private ButtonComponent createTab(String translationKey, int index) {
        ButtonComponent button = Components.button(Component.translatable(translationKey),
                pressed -> this.showTab(index));
        button.textShadow(false);
        return button;
    }

    private void showTab(int index) {
        if (this.currentTab == index) {
            return;
        }
        this.currentTab = index;
        if (this.content == null) {
            return;
        }
        this.content.clearChildren();
        switch (index) {
            case TAB_TRANSCEIVERS -> this.content.child(this.transceiverListPage.root());
            case TAB_CONSOLE -> {
                this.content.child(this.consolePage.root());
                this.consolePage.onShown();
            }
            default -> this.content.child(this.connectionPage.root());
        }
    }

    // ------------------------------------------------------------------
    // 服务端状态回推
    // ------------------------------------------------------------------

    public void onState(HubState state) {
        if (this.statusLabel != null) {
            this.statusLabel.text(Component.translatable(state.status()));
        }
        this.connectionPage.acceptState(state);
        this.transceiverListPage.acceptState(state);
        this.consolePage.acceptState(state);
    }

    public void onPorts(String typeId, List<DiscoveryResult> results) {
        this.connectionPage.acceptPorts(typeId, results);
    }

    public void onNotice(String key) {
        if (this.noticeLabel != null) {
            this.noticeLabel.text(Component.translatable(key));
        }
    }

    // ------------------------------------------------------------------
    // 操作意图上传（一律经服务端校验后执行）
    // ------------------------------------------------------------------

    public void requestState() {
        ClientPlayNetworking.send(new RequestStatePayload());
    }

    public void sendConnect(String typeId, Map<String, String> params) {
        ClientPlayNetworking.send(new ConnectPayload(typeId, new LinkedHashMap<>(params)));
    }

    public void sendDisconnect() {
        ClientPlayNetworking.send(new DisconnectPayload());
    }

    public void sendDiscover(String typeId) {
        ClientPlayNetworking.send(new DiscoverPayload(typeId));
    }

    public void sendTransceiverDetail(int transceiverId) {
        ClientPlayNetworking.send(new OpenTransceiverDetailPayload(transceiverId));
    }

    /**
     * 在指定屏幕坐标处挂载一个弹出菜单，用于描述符驱动的下拉字段。
     * <p>
     * 弹层直接挂在 root 上，嵌套层级最浅；不抬高 z 轴时会被页面内容（嵌套更深、深度更靠前）
     * 整片遮挡，表现为弹出后完全透明。绝对定位挂载还会再叠加一次 root 内边距，这里一并抵消，
     * 保证弹层与触发控件对齐。
     */
    public void openDropdown(double mouseX, double mouseY, Consumer<DropdownComponent> builder) {
        FlowLayout root = this.uiAdapter.rootComponent;
        Insets padding = root.padding().get();
        DropdownComponent dropdown = DropdownComponent.openContextMenu(this, root,
                (parent, component) -> parent.child(component),
                mouseX - padding.left(), mouseY - padding.top(), builder);
        dropdown.zIndex(OVERLAY_Z_INDEX);
    }

    public void closeDropdown(DropdownComponent dropdown) {
        this.uiAdapter.rootComponent.removeChild(dropdown);
    }
}
