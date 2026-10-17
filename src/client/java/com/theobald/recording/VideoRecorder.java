package com.theobald.recording;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-game video recorder. Frames are grabbed on the render thread, then scaled,
 * JPEG-compressed and written to an AVI (MJPEG) file on a background thread.
 * Recording stops by itself after {@link #MAX_SECONDS}.
 */
public final class VideoRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");

    public static final int MAX_SECONDS = 10 * 60;
    public static final int FPS = 15;
    private static final int MAX_WIDTH = 854;
    private static final int JPEG_QUALITY = 60;
    private static final long FRAME_NANOS = 1_000_000_000L / FPS;
    private static final long MAX_NANOS = MAX_SECONDS * 1_000_000_000L;

    private static volatile boolean recording = false;
    private static long startNanos;
    private static long lastCaptureNanos;
    private static int targetWidth;
    private static int targetHeight;
    private static Worker worker;

    private VideoRecorder() {}

    public static boolean isRecording() {
        return recording;
    }

    public static int elapsedSeconds() {
        if (!recording) return 0;
        return (int) ((System.nanoTime() - startNanos) / 1_000_000_000L);
    }

    public static void toggle() {
        if (recording) {
            stop(false);
        } else {
            start();
        }
    }

    public static void start() {
        if (recording) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getFramebufferWidth();
        int h = mc.getWindow().getFramebufferHeight();
        if (w <= 0 || h <= 0) return;

        targetWidth = Math.max(2, Math.min(w, MAX_WIDTH) & ~1);
        targetHeight = Math.max(2, (int) Math.round(h * (double) targetWidth / w) & ~1);

        try {
            String name = "PvP_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path finalFile = Recordings.directory().resolve(name + ".avi");
            worker = new Worker(finalFile, targetWidth, targetHeight);
            worker.start();
        } catch (IOException e) {
            LOGGER.error("Could not start recording", e);
            RecordingHud.notice("Kayıt başlatılamadı");
            return;
        }

        startNanos = System.nanoTime();
        lastCaptureNanos = 0;
        recording = true;
    }

    public static void stop(boolean limitReached) {
        if (!recording) return;
        recording = false;
        if (worker != null) {
            worker.finish();
            worker = null;
        }
        RecordingHud.notice(limitReached ? "Kayıt sona erdi" : "Kayıt kaydedildi");
    }

    /** Called every client tick. */
    public static void tick() {
        if (!recording) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            stop(false); // left the world
        } else if (System.nanoTime() - startNanos >= MAX_NANOS) {
            stop(true);
        }
    }

    /** Called on the render thread after the world has been drawn. */
    public static void onWorldRendered() {
        if (!recording) return;
        long now = System.nanoTime();
        if (now - startNanos >= MAX_NANOS) {
            stop(true);
            return;
        }
        if (now - lastCaptureNanos < FRAME_NANOS) return;
        lastCaptureNanos = now;

        Worker w = worker;
        if (w == null) return;
        if (w.failed) {
            stop(false);
            RecordingHud.notice("Kayıt hatası");
            return;
        }

        Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(fb)) {
            byte[] rgb = toRgb(image, targetWidth, targetHeight);
            w.offer(new Frame(rgb, now - startNanos)); // dropped if the encoder is behind
        } catch (Throwable t) {
            LOGGER.error("Frame capture failed", t);
            stop(false);
            RecordingHud.notice("Kayıt hatası");
        }
    }

    /** Nearest-neighbour downscale of an RGBA NativeImage into packed RGB bytes. */
    private static byte[] toRgb(NativeImage img, int tw, int th) {
        int iw = img.getWidth();
        int ih = img.getHeight();
        byte[] out = new byte[tw * th * 3];
        int[] xs = new int[tw];
        for (int x = 0; x < tw; x++) {
            xs[x] = Math.min(iw - 1, (int) ((x + 0.5) * iw / tw));
        }
        int o = 0;
        for (int y = 0; y < th; y++) {
            int sy = Math.min(ih - 1, (int) ((y + 0.5) * ih / th));
            for (int x = 0; x < tw; x++) {
                int abgr = img.getColor(xs[x], sy);
                out[o++] = (byte) abgr;          // R
                out[o++] = (byte) (abgr >> 8);   // G
                out[o++] = (byte) (abgr >> 16);  // B
            }
        }
        return out;
    }

    private record Frame(byte[] rgb, long timeNanos) {}

    private static final class Worker extends Thread {
        private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(6);
        private final Path finalFile;
        private final Path partFile;
        private final int width;
        private final int height;
        private volatile boolean finishing = false;
        volatile boolean failed = false;

        Worker(Path finalFile, int width, int height) throws IOException {
            super("PvP Camera encoder");
            setDaemon(true);
            this.finalFile = finalFile;
            this.partFile = finalFile.resolveSibling(finalFile.getFileName() + ".part");
            this.width = width;
            this.height = height;
            Files.createDirectories(finalFile.getParent());
        }

        void offer(Frame f) {
            queue.offer(f);
        }

        void finish() {
            finishing = true;
        }

        @Override
        public void run() {
            int written = 0;
            try (AviMjpegWriter avi = new AviMjpegWriter(partFile, width, height, FPS)) {
                long frameNanos = FRAME_NANOS;
                while (true) {
                    Frame f = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (f == null) {
                        if (finishing) break;
                        continue;
                    }
                    byte[] jpeg = encodeJpeg(f.rgb(), width, height);
                    // Keep real-time speed even if some frames were dropped: repeat the frame
                    long wanted = Math.round(f.timeNanos() / (double) frameNanos) + 1;
                    int repeat = (int) Math.max(1, Math.min(30, wanted - avi.getFrameCount()));
                    for (int i = 0; i < repeat; i++) avi.writeFrame(jpeg);
                }
                written = avi.getFrameCount();
            } catch (Throwable t) {
                LOGGER.error("Recording failed", t);
                failed = true;
            }

            try {
                if (!failed && written > 0) {
                    Files.move(partFile, finalFile);
                } else {
                    Files.deleteIfExists(partFile);
                }
            } catch (IOException e) {
                LOGGER.error("Could not finalize recording", e);
            }
        }

        private static byte[] encodeJpeg(byte[] rgb, int w, int h) throws IOException {
            ByteBuffer buf = MemoryUtil.memAlloc(rgb.length);
            try {
                buf.put(rgb).flip();
                ByteArrayOutputStream out = new ByteArrayOutputStream(rgb.length / 8);
                STBIWriteCallback cb = STBIWriteCallback.create((context, data, size) -> {
                    ByteBuffer chunk = MemoryUtil.memByteBuffer(data, size);
                    byte[] tmp = new byte[size];
                    chunk.get(tmp);
                    out.write(tmp, 0, size);
                });
                try {
                    if (STBImageWrite.stbi_write_jpg_to_func(cb, 0L, w, h, 3, buf, JPEG_QUALITY) == 0) {
                        throw new IOException("JPEG encoding failed");
                    }
                } finally {
                    cb.free();
                }
                return out.toByteArray();
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }
}
