package com.theobald.recording;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Listing, deleting and exporting ("download") of saved recordings. */
public final class Recordings {
    private static final Logger LOGGER = LoggerFactory.getLogger("PvP Camera");

    private Recordings() {}

    public record Entry(Path path, String title, String info) {}

    public static Path directory() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("pvp-camera").resolve("recordings");
        Files.createDirectories(dir);
        return dir;
    }

    public static List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory())) {
            files.filter(p -> p.getFileName().toString().endsWith(".avi"))
                    .sorted(Comparator.comparingLong(Recordings::modified).reversed())
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String title = name.substring(0, name.length() - 4);
                        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(modified(p)));
                        result.add(new Entry(p, title, date + "  •  " + size(p)));
                    });
        } catch (IOException e) {
            LOGGER.error("Could not list recordings", e);
        }
        return result;
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String size(Path p) {
        try {
            double mb = Files.size(p) / (1024.0 * 1024.0);
            return String.format("%.1f MB", mb);
        } catch (IOException e) {
            return "?";
        }
    }

    public static void delete(Path p) throws IOException {
        Files.deleteIfExists(p);
    }

    private static boolean isAndroid() {
        return Files.isDirectory(Paths.get("/storage/emulated/0"));
    }

    /** Candidate gallery folders, best first. */
    private static List<Path> galleryCandidates() {
        List<Path> dirs = new ArrayList<>();
        if (isAndroid()) {
            Path root = Paths.get("/storage/emulated/0");
            dirs.add(root.resolve("DCIM").resolve("PvPCamera"));
            dirs.add(root.resolve("Movies").resolve("PvPCamera"));
            dirs.add(root.resolve("Download").resolve("PvPCamera"));
        } else {
            Path home = Paths.get(System.getProperty("user.home", "."));
            Path movies = home.resolve("Movies");
            Path videos = home.resolve("Videos");
            if (Files.isDirectory(movies)) dirs.add(movies.resolve("PvP Camera"));
            dirs.add(videos.resolve("PvP Camera"));
        }
        return dirs;
    }

    /** Copies the recording into the device's video gallery folder. Returns the new file. */
    public static Path exportToGallery(Path source) throws IOException {
        IOException last = null;
        for (Path dir : galleryCandidates()) {
            try {
                Files.createDirectories(dir);
                Path target = dir.resolve(source.getFileName());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                if (isAndroid()) requestMediaScan(target);
                return target;
            } catch (IOException e) {
                last = e;
                LOGGER.warn("Could not export to {}", dir, e);
            }
        }
        throw last != null ? last : new IOException("Galeri klasörü bulunamadı");
    }

    /** Best effort: ask Android to index the file so the gallery shows it right away. */
    private static void requestMediaScan(Path file) {
        try {
            Process p = new ProcessBuilder("am", "broadcast",
                    "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d", "file://" + file)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception ignored) {
            // not available in this environment; Android usually indexes new files on its own
        }
    }
}
