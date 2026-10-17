package com.theobald.recording;

import com.theobald.audio.AudioTap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-game video + audio recorder (AVI / MJPEG + PCM).
 *
 * Render thread: the GPU scales the finished frame into a small framebuffer and an async PBO read-back
 * hands the pixels over (see {@link FrameGrabber}). JPEG compression runs on several background threads,
 * a writer thread puts the frames in order into the file. Game audio is mixed by {@link AudioTap}.
 * Recording stops by itself after {@link #MAX_SECONDS}.
 */
public final class VideoRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");

    public static final int MAX_SECONDS = 10 * 60;
    public static final int FPS = 30;
    private static final int BUFFER_POOL = 8;
    private static final long FRAME_NANOS = 1_000_000_000L / FPS;
    private static final long MAX_NANOS = MAX_SECONDS * 1_000_000_000L;
    private static final long MAX_BYTES = 3_600_000_000L; // AVI uses 32-bit sizes

    private static volatile boolean recording = false;
    private static long startNanos;
    private static long nextCaptureNanos;
    private static Worker worker;

    private VideoRecorder() {}

    private static volatile boolean capturing = false;

    public static boolean isCapturingFrame() {
        return capturing;
    }

    /** Start of a rendered frame: decide whether this frame will be captured (HUD overlay is hidden then). */
    public static void beginFrame() {
        capturing = recording && System.nanoTime() >= nextCaptureNanos;
    }

    /** End of a rendered frame: the framebuffer now holds the finished picture. */
    public static void endFrame() {
        if (capturing) {
            capturing = false;
            onWorldRendered();
        }
    }

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

        Quality q = Quality.current();
        int tw = Math.max(2, Math.min(w, q.width) & ~1);
        int th = Math.max(2, (int) Math.round(h * (double) tw / w) & ~1);

        try {
            String name = "PvP_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path finalFile = Recordings.directory().resolve(name + ".avi");
            worker = new Worker(finalFile, tw, th, q.jpegQuality);
            worker.start();
        } catch (Throwable e) {
            LOGGER.error("Could not start recording", e);
            RecordingHud.notice("Kayıt başlatılamadı");
            worker = null;
            return;
        }

        startNanos = System.nanoTime();
        nextCaptureNanos = startNanos;
        recording = true;
        AudioTap.start(worker.avi, startNanos);
    }

    public static void stop(boolean limitReached) {
        if (!recording) return;
        recording = false;
        capturing = false;
        AudioTap.stop();
        Worker w = worker;
        worker = null;
        if (w != null) {
            w.grabber.close(); // GL objects: must be released on the render thread
            w.finish();
        }
        RecordingHud.notice(limitReached ? "Kayıt sona erdi" : "Kayıt kaydedildi");
    }

    /** Called when the game closes: finish the file properly. */
    public static void shutdown() {
        Worker w = worker;
        stop(false);
        if (w != null) {
            try { w.join(15000); } catch (InterruptedException ignored) { }
        }
    }

    /** Called every client tick. */
    public static void tick() {
        if (!recording) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Worker w = worker;
        if (mc.world == null) {
            stop(false); // left the world
        } else if (System.nanoTime() - startNanos >= MAX_NANOS || (w != null && w.sizeLimitHit)) {
            stop(true);
        } else if (w != null && w.failed) {
            stop(false);
            RecordingHud.notice("Kayıt hatası");
        }
    }

    /** Called on the render thread after the world has been drawn. */
    private static void onWorldRendered() {
        if (!recording) return;
        long now = System.nanoTime();
        if (now - startNanos >= MAX_NANOS) {
            stop(true);
            return;
        }
        Worker w = worker;
        if (w == null) return;

        // keep a steady cadence, but never try to "catch up" with a burst of frames
        nextCaptureNanos += FRAME_NANOS;
        if (now - nextCaptureNanos > FRAME_NANOS) nextCaptureNanos = now;

        w.capture(now);
    }

    private record Pending(Future<byte[]> jpeg, long timeNanos) {}

    private static final class Worker extends Thread {
        final AviMjpegWriter avi;
        private final Path finalFile;
        private final Path partFile;
        private final int width, height, jpegQuality;
        private final FrameGrabber grabber;
        private final BlockingQueue<ByteBuffer> pool = new ArrayBlockingQueue<>(BUFFER_POOL);
        private final BlockingQueue<Pending> pending = new LinkedBlockingQueue<>();
        private final ExecutorService encoders;
        private volatile boolean finishing = false;
        volatile boolean failed = false;
        volatile boolean sizeLimitHit = false;

        Worker(Path finalFile, int width, int height, int jpegQuality) throws IOException {
            super("PvP Camera writer");
            setDaemon(false);
            this.finalFile = finalFile;
            this.partFile = finalFile.resolveSibling(finalFile.getFileName() + ".part");
            this.width = width;
            this.height = height;
            this.jpegQuality = jpegQuality;
            Files.createDirectories(finalFile.getParent());
            this.avi = new AviMjpegWriter(partFile, width, height, FPS, true);
            this.grabber = new FrameGrabber(width, height);
            for (int i = 0; i < BUFFER_POOL; i++) pool.add(MemoryUtil.memAlloc(width * height * 4));

            int cores = Runtime.getRuntime().availableProcessors();
            int threads = Math.max(2, Math.min(4, cores / 2));
            this.encoders = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "PvP Camera jpeg");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }

        /** Render thread. */
        void capture(long now) {
            ByteBuffer buf = pool.poll();
            if (buf == null) return; // encoders are behind: skip this frame (the writer repeats the last one)
            long t = grabber.grab(buf, now);
            if (t < 0) {
                pool.offer(buf);
                return;
            }
            final ByteBuffer frame = buf;
            Future<byte[]> f = encoders.submit(() -> {
                try {
                    return encodeJpeg(frame, width, height, jpegQuality);
                } finally {
                    pool.offer(frame);
                }
            });
            pending.add(new Pending(f, t - startNanos));
        }

        void finish() {
            finishing = true;
        }

        @Override
        public void run() {
            int written = 0;
            byte[] last = null;
            try {
                while (true) {
                    Pending p = pending.poll(100, TimeUnit.MILLISECONDS);
                    if (p == null) {
                        if (finishing) break;
                        continue;
                    }
                    byte[] jpeg = p.jpeg().get();
                    long wanted = Math.round(p.timeNanos() / (double) FRAME_NANOS); // frame slot of this picture
                    int have = avi.getFrameCount();
                    if (wanted < have) continue; // arrived too early for its slot: drop to keep real-time speed
                    // fill gaps (dropped frames) with the previous picture so playback speed stays correct
                    int gap = (int) Math.min(90, wanted - have);
                    if (last != null) {
                        for (int i = 0; i < gap; i++) avi.writeFrame(last);
                    }
                    avi.writeFrame(jpeg);
                    last = jpeg;
                    if (avi.getBytesWritten() > MAX_BYTES) sizeLimitHit = true;
                }
                written = avi.getFrameCount();
            } catch (Throwable t) {
                LOGGER.error("Recording failed", t);
                failed = true;
            }

            encoders.shutdownNow();
            try {
                avi.close();
            } catch (Throwable t) {
                LOGGER.error("Could not close recording file", t);
                failed = true;
            }
            for (ByteBuffer b : pool) MemoryUtil.memFree(b);
            pool.clear();

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

        private static byte[] encodeJpeg(ByteBuffer rgba, int w, int h, int quality) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 6);
            STBIWriteCallback cb = STBIWriteCallback.create((context, data, size) -> {
                byte[] tmp = new byte[size];
                MemoryUtil.memByteBuffer(data, size).get(tmp);
                out.write(tmp, 0, size);
            });
            try {
                rgba.rewind();
                // 4 components: alpha is ignored by the JPEG writer
                if (STBImageWrite.stbi_write_jpg_to_func(cb, 0L, w, h, 4, rgba, quality) == 0) {
                    throw new IOException("JPEG encoding failed");
                }
            } finally {
                cb.free();
            }
            return out.toByteArray();
        }
    }
}
