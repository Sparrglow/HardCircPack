package com.sparrglow.hardcircpack.client.transceiver;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.sparrglow.hardcircpack.transceiver.HardCircTransceiverBlock;
import com.sparrglow.hardcircpack.transceiver.HardCircTransceiverBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 收发器指示灯渲染器。
 * <p>
 * 端子配置存放在 BlockEntity 中，blockstate 无法读取，因此逐端子的启用 / 方向 / 模式指示灯
 * 由这里按方块实体数据绘制。三盏灯都画在方块顶部 45° 斜切面上，法线朝上朝外，
 * 玩家正常站立平视即可读到状态，不需要贴近方块往侧面看。
 * <p>
 * 几何以 NORTH 面斜切面为基准，按端子下标绕 Y 轴旋转 90°，与模型里四个旋转立方体一致。
 */
public class TransceiverBlockEntityRenderer
        implements BlockEntityRenderer<HardCircTransceiverBlockEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("hardcirc_pack", "textures/block/transceiver.png");

    /** 模型坐标 1 像素对应的方块空间长度。 */
    private static final float PIXEL = 1.0F / 16.0F;

    /** 45° 斜切面的方向分量。 */
    private static final float SLOPE = 0.70710678F;

    /** 斜切面与方块侧面的交线：模型坐标 y=9.7、z=0.9。 */
    private static final float RIM_Y = 9.7F;
    private static final float RIM_Z = 0.9F;

    /**
     * 三盏灯沿斜切面宽度方向的区间（模型像素）：启用、方向、模式。
     * 玩家正对方块时 +X 朝向左手侧，因此启用灯放在 X 较大的一侧，读起来才是从左到右。
     */
    private static final float[] LAMP_MIN_X = {9.6F, 7.0F, 4.4F};
    private static final float[] LAMP_MAX_X = {11.6F, 9.0F, 6.4F};

    /** 灯窗沿斜面的起始与结束距离（模型像素），两端留出深色边框。 */
    private static final float LAMP_MIN_T = 0.6F;
    private static final float LAMP_MAX_T = 2.1F;

    /** 灯面沿斜面法线外移的距离（方块单位），避免与斜切面共面而在移动时闪颤。 */
    private static final float NORMAL_OFFSET = 0.02F;

    /**
     * 纹理中 (48,16)-(63,31) 的 16×16 纯白色板，由 setColor 着色成各状态颜色。
     * 取色板中心的小矩形采样并留出余量：任一切片层级下采样到的都是纯白，
     * 不会像之前共用 18/64 单点那样让 mip 混入邻近杂色而闪颤。
     */
    private static final float WHITE_MIN_U = 50.0F / 64.0F;
    private static final float WHITE_MAX_U = 61.0F / 64.0F;
    private static final float WHITE_MIN_V = 18.0F / 64.0F;
    private static final float WHITE_MAX_V = 29.0F / 64.0F;

    private static final float[] ENABLED_ON = {0.72F, 1.0F, 0.92F};
    private static final float[] ENABLED_OFF = {0.16F, 0.19F, 0.22F};
    private static final float[] DIRECTION_OUTPUT = {0.35F, 1.0F, 0.45F};
    private static final float[] DIRECTION_INPUT = {1.0F, 0.68F, 0.22F};

    /** 模式灯颜色：红石 / 数据 / 信号。 */
    private static final float[][] MODE_COLORS = {
            {1.0F, 0.28F, 0.22F},
            {0.32F, 0.62F, 1.0F},
            {1.0F, 0.86F, 0.25F}
    };

    public TransceiverBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(HardCircTransceiverBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        for (int terminal = 0; terminal < HardCircTransceiverBlock.TERMINAL_COUNT; terminal++) {
            poseStack.pushPose();
            poseStack.translate(0.5F, 0.5F, 0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(-terminal * 90.0F));
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            Matrix4f matrix = poseStack.last().pose();

            float radians = (float) Math.toRadians(-terminal * 90.0F);
            float normalX = -SLOPE * (float) Math.sin(radians);
            float normalZ = -SLOPE * (float) Math.cos(radians);

            float[] enabledColor = blockEntity.isTerminalEnabled(terminal) ? ENABLED_ON : ENABLED_OFF;
            lamp(buffer, matrix, 0, enabledColor, normalX, normalZ);

            float[] directionColor = blockEntity.isTerminalOutput(terminal) ? DIRECTION_OUTPUT : DIRECTION_INPUT;
            lamp(buffer, matrix, 1, directionColor, normalX, normalZ);

            int mode = Math.min(blockEntity.getTerminalMode(terminal), MODE_COLORS.length - 1);
            lamp(buffer, matrix, 2, MODE_COLORS[mode], normalX, normalZ);

            poseStack.popPose();
        }
    }

    /**
     * 在 NORTH 斜切面上绘制一盏灯。斜面上一点由沿宽度方向的 x 与沿斜面的距离 t 唯一确定：
     * {@code y = RIM_Y + SLOPE * t}、{@code z = RIM_Z + SLOPE * t}。
     */
    private static void lamp(VertexConsumer buffer, Matrix4f matrix, int index,
                             float[] color, float normalX, float normalZ) {
        float minX = LAMP_MIN_X[index] * PIXEL;
        float maxX = LAMP_MAX_X[index] * PIXEL;
        float minY = (RIM_Y + SLOPE * LAMP_MIN_T) * PIXEL;
        float minZ = (RIM_Z + SLOPE * LAMP_MIN_T) * PIXEL;
        float maxY = (RIM_Y + SLOPE * LAMP_MAX_T) * PIXEL;
        float maxZ = (RIM_Z + SLOPE * LAMP_MAX_T) * PIXEL;

        // 顶点随后会被 PoseStack 绕 Y 旋转到各端子朝向，所以外移量必须用 NORTH 局部空间的法线。
        // 直接拿世界空间法线当偏移会让东/南/西三面方向错位，南面正好被抵消成完全共面（移动时闪颤）。
        float offsetY = SLOPE * NORMAL_OFFSET;
        float offsetZ = -SLOPE * NORMAL_OFFSET;

        // 四个顶点各取白色板小矩形的一角，使 UV 有正常梯度：mip 层级选择稳定。
        vertex(buffer, matrix, minX, minY, minZ, color, offsetY, offsetZ, normalX, normalZ,
                WHITE_MIN_U, WHITE_MIN_V);
        vertex(buffer, matrix, minX, maxY, maxZ, color, offsetY, offsetZ, normalX, normalZ,
                WHITE_MIN_U, WHITE_MAX_V);
        vertex(buffer, matrix, maxX, maxY, maxZ, color, offsetY, offsetZ, normalX, normalZ,
                WHITE_MAX_U, WHITE_MAX_V);
        vertex(buffer, matrix, maxX, minY, minZ, color, offsetY, offsetZ, normalX, normalZ,
                WHITE_MAX_U, WHITE_MIN_V);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, float x, float y, float z,
                               float[] color, float offsetY, float offsetZ,
                               float normalX, float normalZ, float u, float v) {
        buffer.addVertex(matrix, x, y + offsetY, z + offsetZ)
                .setColor(color[0], color[1], color[2], 1.0F)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(normalX, SLOPE, normalZ);
    }
}
