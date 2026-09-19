package cn.woshiikun_1145.mcmod.choco.cstmm.client.util;

import cn.woshiikun_1145.mcmod.choco.cstmm.Cstmm;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * base64 图片 → GPU 纹理 的健壮解码工具（地图卡片背景、战队徽标共用）。
 *
 * 健壮性处理（readImageRobust）：
 * 1) 剥离 "data:image/xxx;base64," 前缀与全部空白字符（换行/空格，多行 base64 常见）；
 * 2) PNG 走 NativeImage.read；其他格式（JPEG 等）用 ImageIO 解码后逐像素转 NativeImage；
 * 3) base64 含非法字符时退回 MimeDecoder（容忍部分变体）。
 *
 * 解码失败缓存 null（同 key 不重试），日志 WARN 带 base64 前 24 字符便于定位坏图。
 */
public final class Base64ImageDecoder {

    private Base64ImageDecoder() {}

    /** 已解码的纹理（含原始图像尺寸，供 cover 等比缩放计算） */
    public record CardTexture(Identifier id, int width, int height) {}

    /** 解码缓存（key 为原始 Base64 字符串；解码失败缓存 null，避免每帧重试刷日志） */
    private static final Map<String, CardTexture> TEXTURE_CACHE = new HashMap<>();
    private static int dynamicTextureIndex = 0;

    /** 解码 base64 图片并注册为动态纹理；空串/失败返回 null */
    public static CardTexture decode(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        if (TEXTURE_CACHE.containsKey(base64)) return TEXTURE_CACHE.get(base64);
        CardTexture tex = null;
        try {
            NativeImage image = readImageRobust(base64);
            tex = registerTexture(image);
        } catch (Exception e) {
            Cstmm.LOGGER.warn("[CSTMM - Base64ImageDecoder] Failed to decode base64 image (first 24 chars: '{}'), using placeholder",
                    base64.substring(0, Math.min(24, base64.length())), e);
        }
        TEXTURE_CACHE.put(base64, tex);
        return tex;
    }

    /** 将 NativeImage 注册为动态纹理（jar 内置资源同样走此入口） */
    public static CardTexture registerTexture(NativeImage image) {
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        Identifier id = Identifier.of("cstmm", "dynamic/card_bg_" + (dynamicTextureIndex++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        return new CardTexture(id, image.getWidth(), image.getHeight());
    }

    /** 健壮的 base64 → NativeImage（详见类注释） */
    private static NativeImage readImageRobust(String base64) throws Exception {
        String s = base64.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        s = s.replaceAll("\\s", "");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            bytes = Base64.getMimeDecoder().decode(s);
        }
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            try {
                return NativeImage.read(in);
            } catch (IOException notPng) {
                // 非 PNG（JPEG/BMP 等）：ImageIO 解码后逐像素转 NativeImage（颜色 ARGB → ABGR）
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) throw notPng;
                NativeImage ni = new NativeImage(img.getWidth(), img.getHeight(), false);
                for (int py = 0; py < img.getHeight(); py++) {
                    for (int px = 0; px < img.getWidth(); px++) {
                        int argb = img.getRGB(px, py);
                        int a = (argb >> 24) & 0xFF;
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        ni.setColor(px, py, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
                return ni;
            }
        }
    }
}
