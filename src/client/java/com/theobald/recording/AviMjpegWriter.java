package com.theobald.recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal AVI writer: one MJPEG video stream + one 16-bit stereo PCM audio stream.
 * No external libraries. Header sizes are patched when the file is closed.
 * writeFrame / writeAudio may be called from different threads.
 */
public final class AviMjpegWriter implements AutoCloseable {
    public static final int AUDIO_RATE = 44100;
    private static final int AUDIO_BLOCK = 4; // 2 channels * 16 bit

    private final FileChannel channel;
    private final int width;
    private final int height;
    private final int fps;
    private final boolean withAudio;

    // positions of header fields that are patched on close
    private int posRiffSize, posTotalFrames, posVideoLength, posAudioLength, posMoviSize, posMoviFourcc;

    private final List<int[]> index = new ArrayList<>(); // {stream(0=video,1=audio), offset, length}
    private long position;
    private int videoFrames;
    private long audioSamples;
    private boolean closed;

    public AviMjpegWriter(Path file, int width, int height, int fps, boolean withAudio) throws IOException {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.withAudio = withAudio;
        this.channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        ByteBuffer h = header();
        writeFully(h);
        this.position = h.limit();
    }

    public synchronized int getFrameCount() {
        return videoFrames;
    }

    public synchronized long getBytesWritten() {
        return position;
    }

    private ByteBuffer header() {
        ByteBuffer b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        cc(b, "RIFF"); posRiffSize = b.position(); b.putInt(0); cc(b, "AVI ");

        cc(b, "LIST"); int hdrlSizePos = b.position(); b.putInt(0); cc(b, "hdrl");
        int hdrlStart = b.position() - 4;

        cc(b, "avih"); b.putInt(56);
        b.putInt(1_000_000 / fps);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0x10);              // AVIF_HASINDEX
        posTotalFrames = b.position(); b.putInt(0);
        b.putInt(0);
        b.putInt(withAudio ? 2 : 1); // streams
        b.putInt(1 << 20);
        b.putInt(width);
        b.putInt(height);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);

        // video stream
        cc(b, "LIST"); b.putInt(4 + 64 + 48); cc(b, "strl");
        cc(b, "strh"); b.putInt(56);
        cc(b, "vids"); cc(b, "MJPG");
        b.putInt(0);
        b.putShort((short) 0); b.putShort((short) 0);
        b.putInt(0);
        b.putInt(1);                 // scale
        b.putInt(fps);               // rate
        b.putInt(0);
        posVideoLength = b.position(); b.putInt(0);
        b.putInt(1 << 20);
        b.putInt(-1);
        b.putInt(0);
        b.putShort((short) 0); b.putShort((short) 0);
        b.putShort((short) width); b.putShort((short) height);
        cc(b, "strf"); b.putInt(40);
        b.putInt(40);
        b.putInt(width);
        b.putInt(height);
        b.putShort((short) 1);
        b.putShort((short) 24);
        cc(b, "MJPG");
        b.putInt(width * height * 3);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);

        if (withAudio) {
            cc(b, "LIST"); b.putInt(4 + 64 + 26); cc(b, "strl");
            cc(b, "strh"); b.putInt(56);
            cc(b, "auds"); b.putInt(0);
            b.putInt(0);
            b.putShort((short) 0); b.putShort((short) 0);
            b.putInt(0);
            b.putInt(1);                 // scale
            b.putInt(AUDIO_RATE);        // rate  -> samples per second
            b.putInt(0);
            posAudioLength = b.position(); b.putInt(0);
            b.putInt(1 << 16);
            b.putInt(-1);
            b.putInt(AUDIO_BLOCK);       // sample size
            b.putShort((short) 0); b.putShort((short) 0); b.putShort((short) 0); b.putShort((short) 0);
            cc(b, "strf"); b.putInt(18);
            b.putShort((short) 1);             // PCM
            b.putShort((short) 2);             // channels
            b.putInt(AUDIO_RATE);
            b.putInt(AUDIO_RATE * AUDIO_BLOCK);
            b.putShort((short) AUDIO_BLOCK);
            b.putShort((short) 16);
            b.putShort((short) 0);             // cbSize
        }
        int hdrlEnd = b.position();
        b.putInt(hdrlSizePos, hdrlEnd - hdrlStart);

        cc(b, "LIST"); posMoviSize = b.position(); b.putInt(0); posMoviFourcc = b.position(); cc(b, "movi");
        b.flip();
        return b;
    }

    private static void cc(ByteBuffer b, String s) {
        b.put(s.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeFully(ByteBuffer b) throws IOException {
        while (b.hasRemaining()) channel.write(b);
    }

    private void writeChunk(String id, byte[] data, int stream) throws IOException {
        int len = data.length;
        int pad = len & 1;
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        cc(b, id);
        b.putInt(len);
        b.flip();
        index.add(new int[]{stream, (int) (position - posMoviFourcc), len});
        writeFully(b);
        writeFully(ByteBuffer.wrap(data));
        if (pad == 1) writeFully(ByteBuffer.wrap(new byte[1]));
        position += 8 + len + pad;
    }

    public synchronized void writeFrame(byte[] jpeg) throws IOException {
        if (closed) return;
        writeChunk("00dc", jpeg, 0);
        videoFrames++;
    }

    /** 16-bit little-endian stereo PCM at {@link #AUDIO_RATE}. */
    public synchronized void writeAudio(byte[] pcm) throws IOException {
        if (closed || !withAudio || pcm.length == 0) return;
        writeChunk("01wb", pcm, 1);
        audioSamples += pcm.length / AUDIO_BLOCK;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            long moviEnd = position;

            ByteBuffer idx = ByteBuffer.allocate(8 + 16 * index.size()).order(ByteOrder.LITTLE_ENDIAN);
            cc(idx, "idx1");
            idx.putInt(16 * index.size());
            for (int[] e : index) {
                cc(idx, e[0] == 0 ? "00dc" : "01wb");
                idx.putInt(e[0] == 0 ? 0x10 : 0);
                idx.putInt(e[1]);
                idx.putInt(e[2]);
            }
            idx.flip();
            writeFully(idx);
            position += idx.limit();

            patch(posRiffSize, (int) (position - 8));
            patch(posTotalFrames, videoFrames);
            patch(posVideoLength, videoFrames);
            if (withAudio) patch(posAudioLength, (int) audioSamples);
            patch(posMoviSize, (int) (moviEnd - posMoviFourcc));
        } finally {
            channel.close();
        }
    }

    private void patch(int pos, int value) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(value).flip();
        channel.write(b, pos);
    }
}
