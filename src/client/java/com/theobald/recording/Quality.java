package com.theobald.recording;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Video quality presets (output width; height follows the screen's aspect ratio) + frame rate setting. */
public enum Quality {
    HD("HD 720p", 1280, 70),
    FHD("Full HD 1080p", 1920, 65),
    QHD("2K 1440p", 2560, 65);

    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");
    private static Quality current = FHD;
    private static int fps = 60;
    /** FPS+ : light recording mode (max 720p, fewer encoder threads, automatic throttling when the game slows down). */
    private static boolean boost = true;
    private static boolean loaded = false;

    public final String label;
    public final int width;
    private final int jpegQuality;

    Quality(String label, int width, int jpegQuality) {
        this.label = label;
        this.width = width;
        this.jpegQuality = jpegQuality;
    }

    /** At 60 fps the JPEG quality is lowered a little so files do not become huge. */
    public int jpeg(int fps) {
        return fps > 30 ? jpegQuality - 8 : jpegQuality;
    }

    public Quality next() {
        Quality[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("pvp-camera").resolve("config.properties");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            try { current = valueOf(p.getProperty("quality", FHD.name())); } catch (Exception ignored) { }
            fps = "30".equals(p.getProperty("fps")) ? 30 : 60;
            boost = !"false".equals(p.getProperty("fpsplus"));
        } catch (Exception e) {
            LOGGER.warn("Could not read settings", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Properties p = new Properties();
            p.setProperty("quality", current.name());
            p.setProperty("fps", Integer.toString(fps));
            p.setProperty("fpsplus", Boolean.toString(boost));
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "PvP Camera");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save settings", e);
        }
    }

    public static synchronized Quality current() {
        load();
        return current;
    }

    public static synchronized int fps() {
        load();
        return fps;
    }

    public static synchronized void set(Quality q) {
        load();
        current = q;
        save();
    }

    public static synchronized void setFps(int f) {
        load();
        fps = f == 30 ? 30 : 60;
        save();
    }

    public static synchronized boolean boost() {
        load();
        return boost;
    }

    public static synchronized void setBoost(boolean b) {
        load();
        boost = b;
        save();
    }
}
