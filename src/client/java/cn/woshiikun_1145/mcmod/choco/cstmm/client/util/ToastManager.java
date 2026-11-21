package cn.woshiikun_1145.mcmod.choco.cstmm.client.util;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 【作用】屏幕右上提示栏（Toast，NVIDIA 风格）：深色横条 + 左侧主题色图标块（白色"!"）+ 白色文字，
 *         从右侧滑入（250ms ease-out）→ 停留 → 向右滑出（250ms ease-out）。独立静态方法
 *         {@link #show(String)} 可在任意线程调用，
 *         由宿主界面每帧调用 {@link #renderToasts} 绘制（游戏内 HUD 与 MatchMenuScreen 两路）。
 *         同一文案 3 秒内重复弹出去重（刷新显示窗口不叠加）；最多同时堆叠 5 条，超出淘汰最旧。
 * 【被谁使用】MvpMusicCache（资源缓存失败提示）等运行时通知；宿主界面渲染。
 */
public final class ToastManager {

    /** 滑入/滑出动画时长（毫秒） */
    private static final long SLIDE_MS = 250;
    /** 同文案去重窗口（毫秒） */
    private static final long DEDUP_MS = 3000;
    /** 最大同时堆叠条数 */
    private static final int MAX_TOASTS = 5;
    /** 默认显示时长（毫秒） */
    private static final long DEFAULT_DURATION_MS = 4000;

    private static final ConcurrentLinkedDeque<Toast> toasts = new ConcurrentLinkedDeque<>();

    private ToastManager() {}

    /** 单条提示（非 record：bornAt 需要在去重刷新时可写） */
    private static final class Toast {
        final String message;
        final long durationMs;
        volatile long bornAt;

        Toast(String message, long durationMs, long bornAt) {
            this.message = message;
            this.durationMs = durationMs;
            this.bornAt = bornAt;
        }
    }

    /**
     * 【作用】弹出一个提示栏（默认显示 4 秒）。线程安全；同文案 3 秒内重复调用刷新显示而不叠加。
     * 【被谁使用】MvpMusicCache（缓存失败）等；后续任何需要顶部通知的场景复用本方法。
     */
    public static void show(String message) {
        show(message, DEFAULT_DURATION_MS);
    }

    /** 弹出提示栏（自定义显示时长毫秒）。 */
    public static void show(String message, long durationMs) {
        if (message == null || message.isBlank()) return;
        long now = System.currentTimeMillis();
        for (Toast t : toasts) {
            if (t.message.equals(message) && now - t.bornAt < DEDUP_MS) {
                t.bornAt = now;
                return;
            }
        }
        toasts.addFirst(new Toast(message, durationMs, now));
        while (toasts.size() > MAX_TOASTS) {
            toasts.removeLast();
        }
    }

    /**
     * 【作用】移除指定文案的未到期提示（"进行中"类提示被结果提示替换时调用，如"正在握手..."→"握手成功"）。
     * 【被谁使用】ClientHandshakeState（握手结果）、ClientNetworkHandler（配置同步完成）。
     */
    public static void dismiss(String message) {
        if (message == null) return;
        toasts.removeIf(t -> t.message.equals(message));
    }

    /**
     * 【作用】宿主界面每帧渲染：在 (rightX, topY) 右对齐向下堆叠。
     *         动画：从右侧滑入（250ms ease-out）→ 停留 → 向右滑出（250ms ease-out）。
     *         单条总宽不超过 maxBarWidth（调用方传窗口宽的 1/3），超长文案自动换行。
     *         accentArgb 为强调色（主题色，ARGB；取 RGB 部分作图标块底色）。
     * 【被谁使用】MatchMenuScreen#render（内容区右上）、CstmmClient 的 HudRenderCallback（游戏内屏幕右上）。
     */
    public static void renderToasts(DrawContext context, TextRenderer tr, int rightX, int topY,
                                    int accentArgb, int maxBarWidth) {
        if (toasts.isEmpty()) return;
        long now = System.currentTimeMillis();
        int accentRgb = accentArgb & 0xFFFFFF;
        int iconSize = 14;
        int padL = 4, gap = 8, padR = 12;
        int lineH = 10;
        // 文案最大宽度 = 上限 - 图标块与内边距（保证单条总宽不超上限）
        int textMaxW = Math.max(40, maxBarWidth - padL - iconSize - gap - padR);
        int y = topY;
        Iterator<Toast> it = toasts.iterator();
        while (it.hasNext()) {
            Toast t = it.next();
            long age = now - t.bornAt;
            if (age >= t.durationMs) {
                it.remove();
                continue;
            }
            // 超长文案换行
            List<OrderedText> lines = tr.wrapLines(Text.literal(t.message), textMaxW);
            int barW = padL + iconSize + gap + padR;
            for (OrderedText line : lines) {
                barW = Math.max(barW, padL + iconSize + gap + tr.getWidth(line) + padR);
            }
            int barH = Math.max(22, lines.size() * lineH + 12);

            // 三段动画：滑入 → 停留 → 滑出（均 ease-out，滑出占显示时长末尾 250ms）
            int offset;
            if (age < SLIDE_MS) {
                offset = Math.round((1f - easeOut(age / (float) SLIDE_MS)) * (barW + 8));
            } else if (age >= t.durationMs - SLIDE_MS) {
                float p = (age - (t.durationMs - SLIDE_MS)) / (float) SLIDE_MS;
                offset = Math.round(easeOut(Math.min(1f, p)) * (barW + 8));
            } else {
                offset = 0;
            }
            int x = rightX - barW + offset;

            // 深色横条底 + 细边框
            context.fill(x, y, x + barW, y + barH, (0xE6 << 24) | 0x1E1E1E);
            context.drawBorder(x, y, barW, barH, (0x55 << 24) | 0xFFFFFF);
            // 左侧主题色图标块（垂直居中）+ 白色 "!"
            int iconY = y + (barH - iconSize) / 2;
            context.fill(x + padL, iconY, x + padL + iconSize, iconY + iconSize, (0xFF << 24) | accentRgb);
            context.drawCenteredTextWithShadow(tr, "!", x + padL + iconSize / 2, iconY + 3, 0xFFFFFFFF);
            // 文案（多行垂直居中）
            int textTop = y + (barH - lines.size() * lineH) / 2;
            int lineIdx = 0;
            for (OrderedText line : lines) {
                context.drawText(tr, line, x + padL + iconSize + gap, textTop + lineIdx * lineH, 0xFFFFFFFF, true);
                lineIdx++;
            }
            y += barH + 6;
        }
    }

    /** ease-out 二次缓动 */
    private static float easeOut(float t) {
        return 1f - (1f - Math.max(0f, Math.min(1f, t))) * (1f - Math.max(0f, Math.min(1f, t)));
    }

    /** 清空全部提示（界面关闭等场景；目前断线不清也无害，预留） */
    public static void clear() {
        toasts.clear();
    }
}
