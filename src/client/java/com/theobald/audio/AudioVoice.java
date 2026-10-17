package com.theobald.audio;

import net.minecraft.util.math.Vec3d;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** State of one playing sound, mirrored from the game's OpenAL Source. */
public final class AudioVoice {
    public volatile float pitch = 1f;
    public volatile float volume = 1f;
    public volatile boolean looping = false;
    public volatile boolean relative = false;
    public volatile boolean attenuate = true;
    public volatile float maxDistance = 16f;
    public volatile Vec3d pos = Vec3d.ZERO;
    public volatile boolean paused = false;
    public volatile boolean stopped = false;

    // set when the buffer is attached
    ShortBuffer samples;
    int channels = 1;
    int sampleRate = 44100;
    int frames = 0;

    // owned by the mixer thread
    double cursor = 0;
    boolean done = false;

    public void setPcm(ByteBuffer pcm, AudioFormat format) {
        if (pcm == null || format == null || format.getSampleSizeInBits() != 16
                || format.getChannels() < 1 || format.getChannels() > 2) {
            samples = null;
            return;
        }
        ByteBuffer d = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        d.position(0);
        d.limit(pcm.limit() > 0 ? pcm.limit() : pcm.capacity());
        this.samples = d.asShortBuffer();
        this.channels = format.getChannels();
        this.sampleRate = (int) format.getSampleRate();
        this.frames = this.samples.limit() / channels;
    }

    boolean playable() {
        return samples != null && frames > 1;
    }
}
