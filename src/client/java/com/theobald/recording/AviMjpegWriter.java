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
 * Minimal AVI container writer for MJPEG (one JPEG per frame).
 * No external libraries are needed. Header sizes are patched when the file is closed.
 */
public final class AviMjpegWriter implements AutoCloseable {
    // Fixed header layout (see the header() method): the 'movi' list starts at byte 212
    private static final int POS_RIFF_SIZE = 4;
    private static final int POS_TOTAL_FRAMES = 48;
    private static final int POS_STREAM_LENGTH = 140;
    private static final int POS_MOVI_SIZE = 216;
    private static final int POS_MOVI_FOURCC = 220;
    private static final int HEADER_SIZE = 224;

    private final FileChannel channel;
    private final int width;
    private final int height;
    private final int fps;
    private final List<int[]> index = new ArrayList<>();
    private long position;
    private int frames;

    public AviMjpegWriter(Path file, int width, int height, int fps) throws IOException {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        writeFully(header());
        this.position = HEADER_SIZE;
    }

    public int getFrameCount() {
        return frames;
    }

    private ByteBuffer header() {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        cc(b, "RIFF"); b.putInt(0); cc(b, "AVI ");
        cc(b, "LIST"); b.putInt(192); cc(b, "hdrl");
        cc(b, "avih"); b.putInt(56);
        b.putInt(1_000_000 / fps);   // microseconds per frame
        b.putInt(0);                 // max bytes per second
        b.putInt(0);                 // padding granularity
        b.putInt(0x10);              // AVIF_HASINDEX
        b.putInt(0);                 // total frames (patched on close)
        b.putInt(0);                 // initial frames
        b.putInt(1);                 // streams
        b.putInt(1 << 20);           // suggested buffer size
        b.putInt(width);
        b.putInt(height);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        cc(b, "LIST"); b.putInt(116); cc(b, "strl");
        cc(b, "strh"); b.putInt(56);
        cc(b, "vids"); cc(b, "MJPG");
        b.putInt(0);                 // flags
        b.putShort((short) 0); b.putShort((short) 0); // priority, language
        b.putInt(0);                 // initial frames
        b.putInt(1);                 // scale
        b.putInt(fps);               // rate
        b.putInt(0);                 // start
        b.putInt(0);                 // length (patched on close)
        b.putInt(1 << 20);           // suggested buffer size
        b.putInt(-1);                // quality
        b.putInt(0);                 // sample size
        b.putShort((short) 0); b.putShort((short) 0);
        b.putShort((short) width); b.putShort((short) height);
        cc(b, "strf"); b.putInt(40);
        b.putInt(40);                // BITMAPINFOHEADER size
        b.putInt(width);
        b.putInt(height);
        b.putShort((short) 1);       // planes
        b.putShort((short) 24);      // bit count
        cc(b, "MJPG");
        b.putInt(width * height * 3);
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0);
        cc(b, "LIST"); b.putInt(0); cc(b, "movi");
        b.flip();
        return b;
    }

    private static void cc(ByteBuffer b, String s) {
        b.put(s.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeFully(ByteBuffer b) throws IOException {
        while (b.hasRemaining()) channel.write(b);
    }

    public void writeFrame(byte[] jpeg) throws IOException {
        int len = jpeg.length;
        int pad = len & 1;
        ByteBuffer b = ByteBuffer.allocate(8 + len + pad).order(ByteOrder.LITTLE_ENDIAN);
        cc(b, "00dc");
        b.putInt(len);
        b.put(jpeg);
        if (pad == 1) b.put((byte) 0);
        b.flip();
        index.add(new int[]{(int) (position - POS_MOVI_FOURCC), len});
        writeFully(b);
        position += 8 + len + pad;
        frames++;
    }

    @Override
    public void close() throws IOException {
        try {
            long moviEnd = position;

            ByteBuffer idx = ByteBuffer.allocate(8 + 16 * index.size()).order(ByteOrder.LITTLE_ENDIAN);
            cc(idx, "idx1");
            idx.putInt(16 * index.size());
            for (int[] e : index) {
                cc(idx, "00dc");
                idx.putInt(0x10); // AVIIF_KEYFRAME
                idx.putInt(e[0]);
                idx.putInt(e[1]);
            }
            idx.flip();
            writeFully(idx);
            position += idx.limit();

            patch(POS_RIFF_SIZE, (int) (position - 8));
            patch(POS_TOTAL_FRAMES, frames);
            patch(POS_STREAM_LENGTH, frames);
            patch(POS_MOVI_SIZE, (int) (moviEnd - POS_MOVI_FOURCC));
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
