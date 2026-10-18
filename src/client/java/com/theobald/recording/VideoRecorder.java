package com.theobald.recording;

import com.theobald.audio.AudioTap;
import net.minecraft.client.MinecraftClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static int fps = 60;
    private static long frameNanos = 1_000_000_000L / 60;
    private static final long MAX_NANOS = MAX_SECONDS * 1_000_000_000L;
    private static final long MAX_BYTES = 3_600_000_000L; // AVI uses 32-bit sizes

    private static volatile boolean recording = false;
    private static long startNanos;
    private static long nextCaptureNanos;
    private static Worker worker;
    /** Stopped workers whose GPU objects still have to be released on the render thread. */
    private static final List<Worker> retiring = new ArrayList<>();

    private VideoRecorder() {}

    private static volatile boolean capturing = false;

    public static boolean isCapturingFrame() {
        return capturing;
    }

    // ---- FPS+ governor (render thread only) -------------------------------------------------------
    private static final long SECOND = 1_000_000_000L;
    /** The capture interval is at most this many times the frame interval (3x = 10 fps when recording at 30). */
    private static final double MAX_SLOWDOWN = 3.0;
    private static long fpsWindowStart = 0;
    private static int fpsFrames = 0;
    private static double baselineFps = 0;                  // game fps measured in the world while NOT recording
    /**
     * Capture interval multiplier. It changes smoothly (back off 25% when the game slows down, creep back 5% per
     * second when it is healthy) so the recording settles near the rate the device can really sustain instead of
     * jumping between 30 / 15 / 10 fps, which is what made recordings look choppy.
     */
    private static double slowdown = 1.0;
    private static long floorInterval = 0;                  // minimum capture interval when the GL read-back is slow (mobile)
    private static long captureInterval = 1_000_000_000L / 60;
    private static int framesCaptured = 0;                  // for the summary shown when recording ends

    /** Frames per second the recorder is capturing at the moment (what the video will actually contain). */
    public static int effectiveFps() {
        return (int) Math.round(SECOND / (double) Math.max(1L, captureInterval));
    }

    /** Frame rate of the video file. */
    public static int nominalFps() {
        return fps;
    }

    /** 0 = full rate; 1 = FPS+ is throttling the capture because the game slowed down. */
    public static int throttleLevel() {
        return effectiveFps() < fps * 0.9 ? 1 : 0;
    }

    private static void updateInterval() {
        captureInterval = Math.max((long) (frameNanos * slowdown), floorInterval);
    }

    private static void resetGovernor() {
        floorInterval = 0;
        slowdown = 1.0;
        framesCaptured = 0;
        updateInterval();
    }

    /** Called once per second with the game's current fps. */
    private static void onFpsSample(double f, long now) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.currentScreen != null) return; // menus / loading screens say nothing about gameplay fps
        if (!recording) {
            baselineFps = baselineFps <= 0 ? f : baselineFps * 0.6 + f * 0.4;
            return;
        }
        if (!Quality.boost() || baselineFps < 25) return;
        double ratio = f / baselineFps;
        if (ratio < 0.85) {
            slowdown = Math.min(MAX_SLOWDOWN, slowdown * 1.25);
            updateInterval();
        } else if (ratio > 0.95 && slowdown > 1.0) {
            slowdown = Math.max(1.0, slowdown / 1.05);
            updateInterval();
        }
    }

    /** Start of a rendered frame: decide whether this frame will be captured (HUD overlay is hidden then). */
    public static void beginFrame() {
        long now = System.nanoTime();
        fpsFrames++;
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        } else if (now - fpsWindowStart >= SECOND) {
            double f = fpsFrames * (double) SECOND / (now - fpsWindowStart);
            fpsFrames = 0;
            fpsWindowStart = now;
            onFpsSample(f, now);
        }
        capturing = recording && now >= nextCaptureNanos - captureInterval / 4;
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
        boolean boost = Quality.boost();
        fps = boost ? Math.min(Quality.fps(), 30) : Quality.fps();
        frameNanos = 1_000_000_000L / fps;
        // FPS+ keeps the recording light: max 720p wide and 30 fps
        int maxW = boost ? Math.min(q.width, 1280) : q.width;
        int tw = Math.max(2, Math.min(w, maxW) & ~1);
        int th = Math.max(2, (int) Math.round(h * (double) tw / w) & ~1);

        try {
            String name = "PvP_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path finalFile = Recordings.directory().resolve(name + ".avi");
            int jq = boost ? Math.min(85, q.jpeg(fps) + 12) : q.jpeg(fps); // fewer frames are encoded, so spend a bit more on quality
            worker = new Worker(finalFile, tw, th, jq, fps, frameNanos);
            worker.start();
        } catch (Throwable e) {
            LOGGER.error("Could not start recording", e);
            RecordingHud.notice("Kayıt başlatılamadı");
            worker = null;
            return;
        }

        startNanos = System.nanoTime();
        nextCaptureNanos = startNanos;
        resetGovernor();
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
            w.finish();
            retiring.add(w); // GL objects are released in tick() once the encoders are done
        }
        double secs = Math.max(1.0, (System.nanoTime() - startNanos) / 1e9);
        int avg = (int) Math.round(framesCaptured / secs);
        String msg = limitReached ? "Kayıt sona erdi" : "Kayıt kaydedildi";
        if (avg < fps * 0.8) {
            msg += " (ort. " + avg + " fps - oyun yavaşladığı için düşük)";
        }
        RecordingHud.notice(msg);
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
        if (!retiring.isEmpty()) {
            retiring.removeIf(r -> {
                if (!r.encodingDone) return false;
                r.grabber.close();
                return true;
            });
        }
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

        // Every capture stands for an exact slot on the video's time grid (not for the jittery moment the game
        // happened to render it). That gives perfectly even frame spacing; only when we really fell behind
        // do we re-sync, and the missed slots then repeat the previous picture.
        long sched = nextCaptureNanos;
        if (now - sched > captureInterval) {
            sched = startNanos + Math.round((now - startNanos) / (double) frameNanos) * frameNanos;
        }
        nextCaptureNanos = sched + captureInterval;

        w.capture(sched);
    }

    private record Pending(Future<byte[]> jpeg, long timeNanos) {}

    private static final class Worker extends Thread {
        final AviMjpegWriter avi;
        final FrameGrabber grabber;
        volatile boolean encodingDone = false;
        volatile boolean failed = false;
        volatile boolean sizeLimitHit = false;

        private final Path finalFile;
        private final Path partFile;
        private final int width, height, jpegQuality, fps;
        private final long frameNanos;
        private final BlockingQueue<Pending> pending = new LinkedBlockingQueue<>();
        private final ExecutorService encoders;
        private volatile boolean finishing = false;
        private final AtomicInteger busy = new AtomicInteger();
        private long grabCost = 0;
        private int costSamples = 0;
        private final int maxBusy;
        private final STBIWriteCallback jpegCb = STBIWriteCallback.create((ctx, data, size) -> SINK.get().write(data, size));

        Worker(Path finalFile, int width, int height, int jpegQuality, int fps, long frameNanos) throws IOException {
            super("PvP Camera writer");
            setDaemon(false);
            setPriority(Thread.NORM_PRIORITY - 1);
            this.finalFile = finalFile;
            this.partFile = finalFile.resolveSibling(finalFile.getFileName() + ".part");
            this.width = width;
            this.height = height;
            this.jpegQuality = jpegQuality;
            this.fps = fps;
            this.frameNanos = frameNanos;
            Files.createDirectories(finalFile.getParent());
            this.avi = new AviMjpegWriter(partFile, width, height, fps, true);
            this.grabber = new FrameGrabber(width, height);

            // leave CPU cores to the game: JPEG encoding uses only a minority of them
            int cores = Runtime.getRuntime().availableProcessors();
            int threads = Quality.boost()
                    ? Math.max(1, Math.min(2, cores / 3))
                    : Math.max(1, Math.min(3, cores / 3));
            this.maxBusy = threads * 2;
            this.encoders = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "PvP Camera jpeg");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }

        /** Render thread. */
        void capture(long now) {
            // encoders are behind: do not even touch the GPU for this frame (the writer repeats the last picture)
            if (busy.get() >= maxBusy) return;
            final long t0 = System.nanoTime();
            final FrameGrabber.Grabbed g = grabber.grab(now);
            if (g != null && Quality.boost()) {
                // measure what a capture really costs the render thread and keep it near 12% of the time:
                // fast devices record at full rate, devices that stall on read-back get the best rate they can afford
                long d = System.nanoTime() - t0;
                grabCost = grabCost == 0 ? d : (long) (grabCost * 0.85 + d * 0.15);
                if (++costSamples >= 5) {
                    floorInterval = Math.max(0L, Math.min(SECOND / 8, grabCost * 8));
                    updateInterval();
                } else if (grabber.slowness() > 0) {
                    floorInterval = grabber.slowness() == 2 ? SECOND / 12 : SECOND / 20; // until we have real numbers
                    updateInterval();
                }
            }
            if (g == null) return; // warming up, or the encoders are behind
            framesCaptured++;
            busy.incrementAndGet();
            Future<byte[]> f;
            try {
                f = encoders.submit(() -> {
                    try {
                        return encodeJpeg(g.buf, width, height, jpegQuality);
                    } finally {
                        grabber.release(g);
                        busy.decrementAndGet();
                    }
                });
            } catch (RuntimeException e) { // pool already shut down
                grabber.release(g);
                busy.decrementAndGet();
                return;
            }
            pending.add(new Pending(f, g.time - startNanos));
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
                    long wanted = Math.round(p.timeNanos() / (double) frameNanos); // frame slot of this picture
                    int have = avi.getFrameCount();
                    if (wanted < have) continue; // slot already filled: drop to keep real-time speed
                    // fill gaps (dropped frames) with the previous picture so playback speed stays correct
                    int gap = (int) Math.min(fps * 3L, wanted - have);
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

            // every encoder task must be finished before the GPU buffers they read from are released
            encoders.shutdown();
            try {
                encoders.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) { }
            encodingDone = true;
            jpegCb.free();

            try {
                avi.close();
            } catch (Throwable t) {
                LOGGER.error("Could not close recording file", t);
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

        /** Collects the JPEG bytes written by stb (one reusable buffer per encoder thread, little garbage). */
        private static final class Sink {
            byte[] data;
            int len;

            Sink(int cap) {
                data = new byte[cap];
            }

            void write(long ptr, int n) {
                if (len + n > data.length) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, len + n));
                MemoryUtil.memByteBuffer(ptr, n).get(data, len, n);
                len += n;
            }
        }

        private static final ThreadLocal<Sink> SINK = ThreadLocal.withInitial(() -> new Sink(1 << 18));

        private byte[] encodeJpeg(ByteBuffer rgba, int w, int h, int quality) throws IOException {
            Sink sink = SINK.get();
            sink.len = 0;
            rgba.rewind();
            // 4 components: alpha is ignored by the JPEG writer
            if (STBImageWrite.stbi_write_jpg_to_func(jpegCb, 0L, w, h, 4, rgba, quality) == 0) {
                throw new IOException("JPEG encoding failed");
            }
            return java.util.Arrays.copyOf(sink.data, sink.len);
        }
    }
}
