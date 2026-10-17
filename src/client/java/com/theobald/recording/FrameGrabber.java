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
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Grabs the finished frame with almost no render-thread cost:
 *  - the GPU scales + flips it into a small framebuffer (glBlitFramebuffer),
 *  - the pixels are read back asynchronously into a ring of PBOs (no GPU stall),
 *  - a PBO is mapped as soon as a GL fence says the GPU is done (never stalls; falls back to a 2 frame lag) and the JPEG encoder reads straight from the mapped memory
 *    (zero copies on the render thread). The PBO is unmapped again once the encoder is done.
 * If the GPU path is not supported (some mobile GL wrappers) it falls back to slower paths.
 * Output is tightly packed RGBA, top row first.
 */
final class FrameGrabber {
    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");

    private static final int RING = 8;   // PBOs: 2 in flight + up to 6 being encoded
    private static final int LAG = 2;    // frames between issuing a read-back and mapping it
    private static final int POOL = 6;   // fallback (copy) buffers
    /** If the GPU still has not finished a read-back after this many frames, map it anyway (stall) so we never starve. */
    private static final int MAX_PENDING = 4;
    /** Only the first few captures check glGetError (it forces a CPU<->GPU sync on some drivers). */
    private static final int VERIFY_FRAMES = 4;

    /** One captured frame. Call {@link #release} when the encoder is finished with it. */
    static final class Grabbed {
        final ByteBuffer buf;
        final long time;
        final int pbo; // ring slot, or -1 for a pooled copy buffer

        Grabbed(ByteBuffer buf, long time, int pbo) {
            this.buf = buf;
            this.time = time;
            this.pbo = pbo;
        }
    }

    final int tw, th;
    private final int size;

    private SimpleFramebuffer small;
    private final int[] pbo = new int[RING];           // 0 = not created yet
    private final int[] state = new int[RING];         // 0 free, 1 read-back in flight, 2 mapped (owned by encoder)
    private final AtomicIntegerArray released = new AtomicIntegerArray(RING);
    private final long[] pboTime = new long[RING];
    private final long[] fence = new long[RING];       // GL sync objects: tell us (without stalling) when a read-back is done
    private boolean useFence = true;
    private int verified = 0;
    private final ArrayDeque<Integer> inFlight = new ArrayDeque<>();

    private final ArrayBlockingQueue<ByteBuffer> pool = new ArrayBlockingQueue<>(POOL);
    private final List<ByteBuffer> allPooled = new ArrayList<>();

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
            if (!ensurePbo(0)) {
                usePbo = false;
                LOGGER.warn("PBO read-back not available, using synchronous read-back");
            }
        } catch (Throwable t) {
            LOGGER.warn("GPU frame scaling not available, using CPU fallback", t);
            gpu = false;
        }
    }

    private static void clearGlErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != 0; i++) { /* drain */ }
    }

    private void dropFence(int slot) {
        if (fence[slot] != 0) {
            try { GL32.glDeleteSync(fence[slot]); } catch (Throwable ignored) { }
            fence[slot] = 0;
        }
    }

    /** True once the GPU has finished the read-back of this slot. Never blocks. */
    private boolean fenceDone(int slot) {
        long f = fence[slot];
        if (f == 0) return false;
        try {
            int r = GL32.glClientWaitSync(f, 0, 0L);
            if (r == GL32.GL_ALREADY_SIGNALED || r == GL32.GL_CONDITION_SATISFIED) {
                dropFence(slot);
                return true;
            }
            if (r == GL32.GL_WAIT_FAILED) {
                useFence = false;
                dropFence(slot);
                return true;
            }
        } catch (Throwable t) {
            useFence = false;
            fence[slot] = 0;
            return true;
        }
        return false;
    }

    /** Creates the PBO of a ring slot on first use (spreads the allocation over several frames). */
    private boolean ensurePbo(int slot) {
        if (pbo[slot] != 0) return true;
        clearGlErrors();
        int id = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        if (GL11.glGetError() != 0) {
            GL15.glDeleteBuffers(id);
            return false;
        }
        pbo[slot] = id;
        return true;
    }

    /** Render thread. Returns the next ready frame, or null (warm-up, encoders busy, error). */
    Grabbed grab(long nowNanos) {
        if (gpu) {
            try {
                return grabGpu(nowNanos);
            } catch (Throwable t) {
                LOGGER.warn("GPU grab failed, switching to CPU fallback", t);
                gpu = false;
                restore();
            }
        }
        return grabCpu(nowNanos);
    }

    /** Any thread. The encoder no longer needs this frame. */
    void release(Grabbed g) {
        if (g.pbo >= 0) {
            released.set(g.pbo, 1);
        } else {
            pool.offer(g.buf);
        }
    }

    private ByteBuffer acquirePooled() {
        ByteBuffer b = pool.poll();
        if (b == null && allPooled.size() < POOL) {
            b = MemoryUtil.memAlloc(size);
            allPooled.add(b);
        }
        return b;
    }

    /** Render thread: unmap PBOs whose encoder has finished so the slot can be reused. */
    private void housekeeping() {
        for (int i = 0; i < RING; i++) {
            if (state[i] == 2 && released.get(i) == 1) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                state[i] = 0;
                released.set(i, 0);
            }
        }
    }

    private int freeSlot() {
        for (int i = 0; i < RING; i++) {
            if (state[i] == 0) return i;
        }
        return -1;
    }

    private Grabbed grabGpu(long now) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer main = mc.getFramebuffer();
        int sw = main.viewportWidth, sh = main.viewportHeight;

        housekeeping();

        int slot = -1;
        ByteBuffer syncBuf = null;
        if (usePbo) {
            slot = freeSlot();
            if (slot < 0) return null;            // encoders are behind: skip this frame
            if (!ensurePbo(slot)) {
                usePbo = false;
                return null;
            }
        } else {
            syncBuf = acquirePooled();
            if (syncBuf == null) return null;
        }

        Grabbed out = null;
        boolean ok = false;
        final boolean verify = verified < VERIFY_FRAMES;
        try {
            if (verify) clearGlErrors();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, small.fbo);
            // destination rectangle is reversed in Y -> flips the image so rows come out top-first
            GL30.glBlitFramebuffer(0, 0, sw, sh, 0, th, tw, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, small.fbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            if (usePbo) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[slot]);
                GL11.glReadPixels(0, 0, tw, th, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
                pboTime[slot] = now;
                state[slot] = 1;
                inFlight.add(slot);
                if (useFence) {
                    try {
                        fence[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                        if (fence[slot] == 0) useFence = false;
                    } catch (Throwable t) {
                        fence[slot] = 0;
                        useFence = false; // no GL 3.2 sync (some mobile wrappers): fall back to frame lag
                    }
                }

                // map the oldest read-back only when the GPU is done with it -> glMapBufferRange never waits
                Integer head = inFlight.peek();
                if (head != null) {
                    int i = head;
                    boolean ready;
                    if (fence[i] != 0) {
                        ready = fenceDone(i) || inFlight.size() > MAX_PENDING;
                    } else {
                        ready = inFlight.size() > LAG;
                    }
                    if (ready) {
                        inFlight.poll();
                        dropFence(i);
                        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                        ByteBuffer m = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, size, GL30.GL_MAP_READ_BIT);
                        if (m != null) {
                            state[i] = 2;
                            released.set(i, 0);
                            out = new Grabbed(m, pboTime[i], i);
                        } else {
                            state[i] = 0;
                        }
                    }
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            } else {
                syncBuf.clear();
                syncBuf.limit(size);
                GL11.glReadPixels(0, 0, tw, th, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, syncBuf);
                out = new Grabbed(syncBuf, now, -1);
            }

            if (verify) {
                int err = GL11.glGetError();
                if (err != 0) throw new IllegalStateException("GL error 0x" + Integer.toHexString(err));
                verified++;
            }
            ok = true;
        } finally {
            restore();
            if (!ok && syncBuf != null) pool.offer(syncBuf);
        }
        return out;
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
    private Grabbed grabCpu(long now) {
        ByteBuffer dst = acquirePooled();
        if (dst == null) return null;
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
            return new Grabbed(dst, now, -1);
        } catch (Throwable t) {
            LOGGER.error("CPU frame grab failed", t);
            pool.offer(dst);
            return null;
        }
    }

    /** Render thread, only after every encoder has finished with its frame. */
    void close() {
        try {
            for (int i = 0; i < RING; i++) {
                if (state[i] == 2) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < RING; i++) dropFence(i);
            for (int i = 0; i < RING; i++) {
                if (pbo[i] != 0) GL15.glDeleteBuffers(pbo[i]);
            }
            if (small != null) small.delete();
        } catch (Throwable ignored) {
            // best effort
        }
        for (ByteBuffer b : allPooled) MemoryUtil.memFree(b);
        allPooled.clear();
    }
}
