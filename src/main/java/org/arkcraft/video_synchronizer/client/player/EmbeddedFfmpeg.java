package org.arkcraft.video_synchronizer.client.player;

import net.minecraft.client.Minecraft;
import org.arkcraft.video_synchronizer.Main;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class EmbeddedFfmpeg {
    private static final String BUNDLE_ID = "ffmpeg-n7.1.5-12-g1fdbca85aa-lgpl-shared";
    private static final String COMPLETE_MARKER = ".complete";
    private static final String LINK_MANIFEST = ".video-synchronizer-links";

    private static volatile Executables resolvedExecutables;

    private EmbeddedFfmpeg() {
    }

    static String ffmpegExecutable() {
        return executables().ffmpeg();
    }

    static String ffprobeExecutable() {
        return executables().ffprobe();
    }

    static ProcessBuilder processBuilder(List<String> command) {
        Executables current = executables();
        ProcessBuilder builder = new ProcessBuilder(command);
        if (current.libraryPath() != null) {
            String inherited = builder.environment().get("LD_LIBRARY_PATH");
            String libraryPath = current.libraryPath();
            if (inherited != null && !inherited.isBlank()) {
                libraryPath += File.pathSeparator + inherited;
            }
            builder.environment().put("LD_LIBRARY_PATH", libraryPath);
        }
        return builder;
    }

    private static Executables executables() {
        Executables current = resolvedExecutables;
        if (current != null) {
            return current;
        }
        synchronized (EmbeddedFfmpeg.class) {
            if (resolvedExecutables == null) {
                resolvedExecutables = resolveExecutables();
            }
            return resolvedExecutables;
        }
    }

    private static Executables resolveExecutables() {
        String configured = System.getProperty("video_synchronizer.ffmpeg");
        if (configured != null && !configured.isBlank()) {
            return externalExecutables(configured);
        }

        Platform platform = currentPlatform();
        if (platform == null) {
            Main.LOGGER.info("No embedded FFmpeg target matches OS '{}' and architecture '{}'; "
                            + "falling back to PATH",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return pathExecutables();
        }
        String resource = resourcePath(platform);
        if (!resourceExists(resource)) {
            Main.LOGGER.info("This mod JAR does not contain FFmpeg for {}; falling back to PATH",
                    platform.displayName());
            return pathExecutables();
        }

        try {
            Path directory = installEmbeddedBundle(platform, resource);
            Main.LOGGER.info("Using embedded FFmpeg LGPL shared build for {}",
                    platform.displayName());
            return new Executables(executablePath(directory, platform, "ffmpeg").toString(),
                    executablePath(directory, platform, "ffprobe").toString(),
                    platform.windows() ? null : directory.resolve("lib").toString());
        } catch (IOException exception) {
            Main.LOGGER.warn("Could not prepare embedded FFmpeg; falling back to PATH", exception);
            return pathExecutables();
        }
    }

    private static Path installEmbeddedBundle(Platform platform, String resource)
            throws IOException {
        Path cacheRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("video_synchronizer").resolve("ffmpeg");
        Files.createDirectories(cacheRoot);
        Path installDirectory = cacheRoot.resolve(BUNDLE_ID).resolve(platform.id());
        Path lockPath = cacheRoot.resolve(platform.id() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            if (isComplete(installDirectory, platform)) {
                return installDirectory;
            }
            deleteTree(installDirectory);
            Path stagingDirectory = Files.createTempDirectory(cacheRoot, BUNDLE_ID + ".tmp-");
            try {
                extractBundle(resource, platform, stagingDirectory);
                Files.writeString(stagingDirectory.resolve(COMPLETE_MARKER), BUNDLE_ID,
                        StandardOpenOption.CREATE_NEW);
                Files.createDirectories(installDirectory.getParent());
                try {
                    Files.move(stagingDirectory, installDirectory,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(stagingDirectory, installDirectory);
                }
            } finally {
                deleteTree(stagingDirectory);
            }
        }
        if (!isComplete(installDirectory, platform)) {
            throw new IOException("Embedded FFmpeg extraction did not produce the required files");
        }
        return installDirectory;
    }

    private static boolean isComplete(Path directory, Platform platform) {
        Path ffmpeg = executablePath(directory, platform, "ffmpeg");
        Path ffprobe = executablePath(directory, platform, "ffprobe");
        if (!Files.isRegularFile(ffmpeg) || !Files.isRegularFile(ffprobe)
                || (!platform.windows() && (!Files.isExecutable(ffmpeg)
                || !Files.isExecutable(ffprobe)))) {
            return false;
        }
        try {
            return BUNDLE_ID.equals(Files.readString(directory.resolve(COMPLETE_MARKER)));
        } catch (IOException exception) {
            return false;
        }
    }

    private static void extractBundle(String resource, Platform platform, Path destination)
            throws IOException {
        Map<String, String> symbolicLinks = new LinkedHashMap<>();
        int extractedFiles = 0;
        try (InputStream resourceInput = EmbeddedFfmpeg.class.getResourceAsStream(resource)) {
            if (resourceInput == null) {
                throw new IOException("Embedded FFmpeg resource is missing: " + resource);
            }
            try (ZipInputStream zip = new ZipInputStream(resourceInput)) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null;
                     entry = zip.getNextEntry()) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (LINK_MANIFEST.equals(entry.getName())) {
                        parseSymbolicLinks(new String(zip.readAllBytes(), StandardCharsets.UTF_8),
                                symbolicLinks);
                        continue;
                    }
                    Path relative = runtimePath(entry.getName(), platform);
                    if (relative == null) {
                        continue;
                    }
                    Path target = destination.resolve(relative).normalize();
                    if (!target.startsWith(destination)) {
                        throw new IOException("Unsafe FFmpeg archive entry: " + entry.getName());
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    extractedFiles++;
                }
            }
        }

        restoreSymbolicLinks(symbolicLinks, platform, destination);
        Path ffmpeg = executablePath(destination, platform, "ffmpeg");
        Path ffprobe = executablePath(destination, platform, "ffprobe");
        if (extractedFiles < 2 || !Files.isRegularFile(ffmpeg)
                || !Files.isRegularFile(ffprobe)) {
            throw new IOException("FFmpeg archive is missing required executables");
        }
        if (!platform.windows()) {
            makeExecutable(ffmpeg);
            makeExecutable(ffprobe);
        }
    }

    private static Path runtimePath(String entryName, Platform platform) throws IOException {
        Path relative = safeRelativePath(entryName);
        if (relative == null) {
            return null;
        }
        String normalized = relative.toString().replace('\\', '/');
        if (normalized.equals("bin/ffplay") || normalized.equals("bin/ffplay.exe")) {
            return null;
        }
        if (normalized.startsWith("bin/")
                || (!platform.windows() && normalized.startsWith("lib/"))) {
            return relative;
        }
        return null;
    }

    private static void parseSymbolicLinks(String manifest, Map<String, String> links)
            throws IOException {
        for (String line : manifest.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IOException("Invalid FFmpeg symbolic-link manifest entry");
            }
            links.put(line.substring(0, separator), line.substring(separator + 1));
        }
    }

    private static void restoreSymbolicLinks(Map<String, String> links, Platform platform,
                                             Path destination) throws IOException {
        for (Map.Entry<String, String> entry : links.entrySet()) {
            Path linkRelative = runtimePath(entry.getKey(), platform);
            Path targetRelative = runtimePath(entry.getValue(), platform);
            if (linkRelative == null || targetRelative == null) {
                continue;
            }
            Path link = destination.resolve(linkRelative).normalize();
            Path target = destination.resolve(targetRelative).normalize();
            if (!link.startsWith(destination) || !target.startsWith(destination)
                    || !Files.isRegularFile(target)) {
                throw new IOException("Invalid FFmpeg symbolic link: " + entry.getKey());
            }
            Files.createDirectories(link.getParent());
            Files.deleteIfExists(link);
            try {
                Files.createSymbolicLink(link, link.getParent().relativize(target));
            } catch (UnsupportedOperationException | SecurityException | IOException exception) {
                Files.copy(target, link, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path safeRelativePath(String value) throws IOException {
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IOException("Unsafe FFmpeg archive path: " + value);
        }
        return path.getNameCount() == 0 ? null : path;
    }

    private static void makeExecutable(Path executable) throws IOException {
        if (!executable.toFile().setExecutable(true, true) && !Files.isExecutable(executable)) {
            throw new IOException("Could not mark FFmpeg executable: " + executable);
        }
    }

    private static Path executablePath(Path directory, Platform platform, String executable) {
        String suffix = platform.windows() ? ".exe" : "";
        return directory.resolve("bin").resolve(executable + suffix);
    }

    private static String resourcePath(Platform platform) {
        return "/META-INF/ffmpeg/" + platform.id() + "/ffmpeg.zip";
    }

    private static boolean resourceExists(String resource) {
        try (InputStream input = EmbeddedFfmpeg.class.getResourceAsStream(resource)) {
            return input != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private static Platform currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = "aarch64".equals(architecture) || "arm64".equals(architecture);
        boolean amd64 = "amd64".equals(architecture) || "x86_64".equals(architecture);
        if (os.startsWith("windows")) {
            if (arm64) {
                return new Platform("windows-aarch64", true, "Windows ARM64");
            }
            if (amd64) {
                return new Platform("windows-x86_64", true, "Windows AMD64");
            }
        }
        if (os.startsWith("linux")) {
            if (arm64) {
                return new Platform("linux-aarch64", false, "Linux ARM64");
            }
            if (amd64) {
                return new Platform("linux-x86_64", false, "Linux AMD64");
            }
        }
        return null;
    }

    private static Executables externalExecutables(String configured) {
        Path path = Path.of(configured);
        Path fileName = path.getFileName();
        if (fileName == null || path.getParent() == null) {
            String probe = configured.toLowerCase(Locale.ROOT).endsWith(".exe")
                    ? "ffprobe.exe" : "ffprobe";
            return new Executables(configured, probe, null);
        }
        String probeName = fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                ? "ffprobe.exe" : "ffprobe";
        return new Executables(configured, path.getParent().resolve(probeName).toString(), null);
    }

    private static Executables pathExecutables() {
        return isWindows() ? new Executables("ffmpeg.exe", "ffprobe.exe", null)
                : new Executables("ffmpeg", "ffprobe", null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .startsWith("windows");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Platform(String id, boolean windows, String displayName) {
    }

    private record Executables(String ffmpeg, String ffprobe, String libraryPath) {
    }
}
