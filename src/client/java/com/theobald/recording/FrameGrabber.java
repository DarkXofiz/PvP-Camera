package com.theobald.recording;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Grabs the finished frame with as little render-thread cost as possible:
 * the GPU scales + flips it into a small framebuffer (glBlitFramebuffer) and the pixels
 * are read back asynchronously through a pair of PBOs (one frame of latency, no GPU stall).
 * If the GPU path is not supported (some mobile GL wrappers) it falls back to a CPU path.
 * Output is tightly packed RGBA, top row first.
 */
final class FrameGrabber {
    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");

    final int tw, th;
    private final int size;

    private SimpleFramebuffer small;
    private final int[] pbo = new int[2];
    private final long[] pboTime = new long[2];
    private final boolean[] pboFull = new boolean[2];
    private int cur = 0;
    private boolean gpu = true;
    private boolean usePbo = true;

    FrameGrabber(int tw, int th) {
        this.tw = tw;
        this.th = th;
        this.size = tw * th * 4;
        try {
            clearGlErrors();
            small = new SimpleFramebuffer(tw, th, false, MinecraftClient.IS_SYSTEM_MAC);
            small.setClearColor(0f, 0f, 0f, 1f);
            pbo[0] = GL15.glGenBuffers();
            pbo[1] = GL15.glGenBuffers();
            for (int i = 0; i < 2; i++) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            if (GL11.glGetError() != 0) {
                usePbo = false;
                LOGGER.warn("PBO readback not available, using synchronous readback");
            }
        } catch (Throwable t) {
            LOGGER.warn("GPU frame scaling not available, using CPU fallback", t);
            gpu = false;
        }
    }

    private static void clearGlErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != 0; i++) { /* drain */ }
    }

    /**
     * Captures the current main framebuffer into {@code dst} (cleared + filled, then flipped for reading).
     * @return capture time of the frame that was written, or -1 if nothing was written (pipeline warm-up / error)
     */
    long grab(ByteBuffer dst, long nowNanos) {
        if (gpu) {
            try {
                long t = grabGpu(dst, nowNanos);
                return t;
            } catch (Throwable t) {
                LOGGER.warn("GPU grab failed, switching to CPU fallback", t);
                gpu = false;
                restore();
            }
        }
        return grabCpu(dst, nowNanos);
    }

    private long grabGpu(ByteBuffer dst, long now) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer main = mc.getFramebuffer();
        int sw = main.viewportWidth, sh = main.viewportHeight;

        clearGlErrors();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, small.fbo);
        // destination rectangle is reversed in Y -> flips the image so rows come out top-first
        GL30.glBlitFramebuffer(0, 0, sw, sh, 0, th, tw, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, small.fbo);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

        long result = -1;
        if (usePbo) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[cur]);
            GL11.glReadPixels(0, 0, tw, th, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            pboTime[cur] = now;
            pboFull[cur] = true;

            int prev = 1 - cur;
            if (pboFull[prev]) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[prev]);
                ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, size, GL30.GL_MAP_READ_BIT);
                if (mapped != null) {
                    dst.clear();
                    dst.put(mapped);
                    dst.flip();
                    result = pboTime[prev];
                }
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                pboFull[prev] = false;
            }
            cur = prev;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        } else {
            dst.clear();
            dst.limit(size);
            GL11.glReadPixels(0, 0, tw, th, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, dst);
            result = now;
        }

        int err = GL11.glGetError();
        restore();
        if (err != 0) {
            throw new IllegalStateException("GL error 0x" + Integer.toHexString(err));
        }
        return result;
    }

    private void restore() {
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            MinecraftClient.getInstance().getFramebuffer().beginWrite(true);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Slow path: full-size screenshot + nearest-neighbour downscale on the CPU. */
    private long grabCpu(ByteBuffer dst, long now) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try (NativeImage img = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) {
            int iw = img.getWidth(), ih = img.getHeight();
            dst.clear();
            int[] xs = new int[tw];
            for (int x = 0; x < tw; x++) xs[x] = Math.min(iw - 1, (int) ((x + 0.5) * iw / tw));
            for (int y = 0; y < th; y++) {
                int sy = Math.min(ih - 1, (int) ((y + 0.5) * ih / th));
                for (int x = 0; x < tw; x++) {
                    int abgr = img.getColor(xs[x], sy);
                    dst.put((byte) abgr).put((byte) (abgr >> 8)).put((byte) (abgr >> 16)).put((byte) 0xFF);
                }
            }
            dst.flip();
            return now;
        } catch (Throwable t) {
            LOGGER.error("CPU frame grab failed", t);
            return -1;
        }
    }

    void close() {
        try {
            if (small != null) small.delete();
            if (pbo[0] != 0) GL15.glDeleteBuffers(pbo[0]);
            if (pbo[1] != 0) GL15.glDeleteBuffers(pbo[1]);
        } catch (Throwable ignored) {
            // best effort
        }
    }
}
