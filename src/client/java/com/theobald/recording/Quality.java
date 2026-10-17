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

/** Video quality presets (output width; height follows the screen's aspect ratio). */
public enum Quality {
    HD("HD 720p", 1280, 70),
    FHD("Full HD 1080p", 1920, 65),
    QHD("2K 1440p", 2560, 65);

    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");
    private static Quality current;

    public final String label;
    public final int width;
    public final int jpegQuality;

    Quality(String label, int width, int jpegQuality) {
        this.label = label;
        this.width = width;
        this.jpegQuality = jpegQuality;
    }

    public Quality next() {
        Quality[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("pvp-camera").resolve("config.properties");
    }

    public static synchronized Quality current() {
        if (current == null) {
            current = FHD;
            Path f = file();
            if (Files.isRegularFile(f)) {
                try (InputStream in = Files.newInputStream(f)) {
                    Properties p = new Properties();
                    p.load(in);
                    current = valueOf(p.getProperty("quality", FHD.name()));
                } catch (Exception e) {
                    LOGGER.warn("Could not read quality setting", e);
                }
            }
        }
        return current;
    }

    public static synchronized void set(Quality q) {
        current = q;
        try {
            Files.createDirectories(file().getParent());
            Properties p = new Properties();
            p.setProperty("quality", q.name());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "PvP Camera");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save quality setting", e);
        }
    }
}
