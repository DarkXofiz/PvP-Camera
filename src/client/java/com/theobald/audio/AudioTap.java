package com.theobald.audio;

import com.theobald.recording.AviMjpegWriter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Re-creates the game's sound output in software while recording.
 * The mixins feed it every sound the game starts (position, volume, pitch, ...); a mixer thread renders
 * them in real time (distance attenuation + left/right panning relative to the camera) into 44.1 kHz
 * stereo PCM and writes it into the video file.
 * Streamed sounds (music, jukebox) are not captured; everything else is.
 */
public final class AudioTap {
    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");
    private static final int RATE = AviMjpegWriter.AUDIO_RATE;

    private static final List<AudioVoice> VOICES = new CopyOnWriteArrayList<>();
    private static volatile boolean active = false;
    private static Thread mixer;

    private AudioTap() {}

    public static boolean isActive() {
        return active;
    }

    /** Called from Source.play(): a sound just started. */
    public static void onPlay(AudioVoice v) {
        if (!active || !v.playable()) return;
        v.cursor = 0;
        v.done = false;
        VOICES.add(v);
    }

    public static synchronized void start(AviMjpegWriter writer, long startNanos) {
        if (active) return;
        VOICES.clear();
        active = true;
        mixer = new Thread(() -> run(writer, startNanos), "PvP Camera audio mixer");
        mixer.setDaemon(true);
        mixer.setPriority(Thread.NORM_PRIORITY - 1);
        mixer.start();
    }

    /** Stops mixing, flushes the remaining audio into the file and waits for the mixer thread. */
    public static synchronized void stop() {
        if (!active) return;
        active = false;
        Thread t = mixer;
        mixer = null;
        if (t != null) {
            try { t.join(3000); } catch (InterruptedException ignored) { }
        }
        VOICES.clear();
    }

    private static void run(AviMjpegWriter writer, long startNanos) {
        long produced = 0;
        boolean last = false;
        try {
            while (true) {
                boolean stopping = !active;
                long now = System.nanoTime();
                long target = (now - startNanos) * RATE / 1_000_000_000L;
                long need = target - produced;
                if (need >= 1024 || (stopping && need > 0)) {
                    int frames = (int) Math.min(need, 8192);
                    byte[] pcm = mix(frames);
                    writer.writeAudio(pcm);
                    produced += frames;
                    continue; // maybe more to catch up
                }
                if (stopping) break;
                Thread.sleep(20);
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Audio mixer stopped", e);
        }
    }

    private static byte[] mix(int frames) {
        float[] acc = new float[frames * 2];

        Vec3d lp = Vec3d.ZERO;
        float yaw = 0f;
        try {
            Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
            if (cam != null) {
                lp = cam.getPos();
                yaw = cam.getYaw();
            }
        } catch (Throwable ignored) { }
        double yr = Math.toRadians(yaw);
        // listener's right vector on the XZ plane (yaw 0 faces +Z, right is -X)
        double rx = -Math.cos(yr), rz = -Math.sin(yr);

        for (AudioVoice v : VOICES) {
            if (v.done || v.stopped) continue;
            if (v.paused) continue;

            float gain = v.volume;
            float left = 1f, right = 1f;
            if (v.channels == 1) {
                if (v.relative) {
                    left = right = 0.7071f;
                } else {
                    Vec3d p = v.pos;
                    double dx = p.x - lp.x, dy = p.y - lp.y, dz = p.z - lp.z;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (v.attenuate) {
                        double max = Math.max(1.0001, v.maxDistance);
                        double g = dist <= 1 ? 1 : 1 - (dist - 1) / (max - 1);
                        gain *= (float) Math.max(0, Math.min(1, g));
                    }
                    double pan = dist > 1e-4 ? (dx * rx + dz * rz) / dist : 0;
                    pan = Math.max(-1, Math.min(1, pan));
                    double ang = (pan + 1) * Math.PI / 4; // equal-power pan
                    left = (float) Math.cos(ang);
                    right = (float) Math.sin(ang);
                }
            } else if (v.attenuate && !v.relative) {
                Vec3d p = v.pos;
                double dist = p.distanceTo(lp);
                double max = Math.max(1.0001, v.maxDistance);
                double g = dist <= 1 ? 1 : 1 - (dist - 1) / (max - 1);
                gain *= (float) Math.max(0, Math.min(1, g));
            }
            if (gain <= 0.0001f) {
                // still advance so the sound ends on time
                advance(v, frames);
                continue;
            }

            double step = (double) v.sampleRate * Math.max(0.05f, v.pitch) / RATE;
            double cur = v.cursor;
            int len = v.frames;
            int ch = v.channels;
            for (int i = 0; i < frames; i++) {
                int ip = (int) cur;
                if (ip >= len - 1) {
                    if (v.looping) {
                        cur -= len - 1;
                        ip = (int) cur;
                        if (ip >= len - 1) ip = 0;
                    } else {
                        v.done = true;
                        break;
                    }
                }
                float f = (float) (cur - ip);
                float l, r;
                if (ch == 1) {
                    float s = lerp(v.samples.get(ip), v.samples.get(ip + 1), f);
                    l = s * left;
                    r = s * right;
                } else {
                    l = lerp(v.samples.get(ip * 2), v.samples.get((ip + 1) * 2), f);
                    r = lerp(v.samples.get(ip * 2 + 1), v.samples.get((ip + 1) * 2 + 1), f);
                }
                acc[i * 2] += l * gain;
                acc[i * 2 + 1] += r * gain;
                cur += step;
            }
            v.cursor = cur;
        }
        VOICES.removeIf(v -> v.done || v.stopped);

        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames * 2; i++) {
            float s = acc[i];
            if (s > 32767f) s = 32767f; else if (s < -32768f) s = -32768f;
            int sv = (int) s;
            out[i * 2] = (byte) sv;
            out[i * 2 + 1] = (byte) (sv >> 8);
        }
        return out;
    }

    private static void advance(AudioVoice v, int outFrames) {
        double step = (double) v.sampleRate * Math.max(0.05f, v.pitch) / RATE;
        v.cursor += step * outFrames;
        if (v.cursor >= v.frames - 1) {
            if (v.looping) v.cursor %= Math.max(1, v.frames - 1);
            else v.done = true;
        }
    }

    private static float lerp(short a, short b, float f) {
        return a + (b - a) * f;
    }
}
