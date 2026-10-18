package com.theobald.recording;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** On-screen overlay: REC timer while recording + a short message at the top when it ends. */
public final class RecordingHud {
    private static final long NOTICE_MS = 4000;
    private static final long FADE_MS = 1000;

    private static volatile String noticeText = "";
    private static volatile long noticeUntil = 0;
    private static String cachedHud = "";
    private static int cachedSec = -1, cachedLevel = -1;

    private RecordingHud() {}

    public static void notice(String text) {
        noticeText = text;
        noticeUntil = System.currentTimeMillis() + NOTICE_MS;
    }

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        if (VideoRecorder.isCapturingFrame()) return;

        if (VideoRecorder.isRecording()) {
            int s = VideoRecorder.elapsedSeconds();
            int lvl = VideoRecorder.effectiveFps();
            if (s != cachedSec || lvl != cachedLevel) { // build the text once per second, not every frame
                cachedSec = s;
                cachedLevel = lvl;
                cachedHud = String.format("REC %02d:%02d / %02d:%02d", s / 60, s % 60,
                        VideoRecorder.MAX_SECONDS / 60, VideoRecorder.MAX_SECONDS % 60)
                        + (VideoRecorder.throttleLevel() > 0 ? "  FPS+ " + lvl + " fps" : "");
            }
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                ctx.fill(6, 6, 12, 12, 0xFFFF2020);
            }
            ctx.drawTextWithShadow(tr, cachedHud, 16, 5, 0xFFFFFFFF);
        }

        long left = noticeUntil - System.currentTimeMillis();
        if (left > 0) {
            int alpha = left >= FADE_MS ? 255 : (int) (255 * left / FADE_MS);
            if (alpha > 8) {
                int color = (alpha << 24) | 0xFF5555;
                ctx.drawCenteredTextWithShadow(tr, noticeText, ctx.getScaledWindowWidth() / 2, 12, color);
            }
        }
    }
}
