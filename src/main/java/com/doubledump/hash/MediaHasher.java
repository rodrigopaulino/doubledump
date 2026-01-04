package com.doubledump.hash;

import com.doubledump.config.DoubleDumpEnvironment;
import com.doubledump.model.FileHash;
import com.doubledump.storage.CacheStore;
import com.doubledump.system.CommandLocator;
import com.doubledump.system.ProcessResult;
import com.doubledump.system.ProcessUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class MediaHasher {
    private final CacheStore cache;
    private final DoubleDumpEnvironment env;

    public MediaHasher(DoubleDumpEnvironment env, CacheStore cache) {
        this.env = env;
        this.cache = cache;
    }

    public FileHash hashWithCache(Path file) {
        try {
            validateFile(file);
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long mtime = attrs.lastModifiedTime().toMillis() / 1000;
            long size = attrs.size();
            String absolutePath = file.toAbsolutePath().toString();
            if (cache != null) {
                Optional<String> cached = cache.lookup(absolutePath, mtime, size);
                if (cached.isPresent()) {
                    return new FileHash(cached.get(), file);
                }
            }
            String hash = computeNormalizedHash(file);
            if (cache != null) {
                cache.store(absolutePath, mtime, size, hash);
            }
            return new FileHash(hash, file);
        } catch (HasherException ex) {
            Path offender = ex.problematic() != null ? ex.problematic() : file;
            env.logSkippedInput(ex.reason(), offender, ex.detail());
            return null;
        } catch (IOException ex) {
            env.logSkippedInput("io-error", file, ex.getMessage());
            return null;
        }
    }

    public String computeHash(Path file) throws HasherException {
        try {
            validateFile(file);
            return computeNormalizedHash(file);
        } catch (IOException ex) {
            throw new HasherException("io-error", file, ex.getMessage());
        }
    }

    public void normalizeRasterForCompare(Path input, Path output) throws HasherException {
        String ext = extension(input.getFileName().toString());
        if (!Set.of("png", "gif", "jpg", "jpeg", "dng").contains(ext)) {
            throw new HasherException("unsupported-extension:" + ext, input, "compare-pixels only supports raster inputs");
        }
        try {
            normalizeRaster(input, output);
        } catch (IOException ex) {
            throw new HasherException("io-error", input, ex.getMessage());
        }
    }

    private void validateFile(Path file) throws IOException, HasherException {
        if (!Files.exists(file)) {
            throw new HasherException("missing-file", file, "missing");
        }
        if (!Files.isRegularFile(file)) {
            throw new HasherException("not-regular-file", file, "not regular");
        }
        if (!Files.isReadable(file)) {
            throw new HasherException("unreadable-file", file, "permission denied");
        }
        if (Files.size(file) == 0) {
            throw new HasherException("zero-byte-file", file, "empty input");
        }
    }

    private String computeNormalizedHash(Path file) throws IOException, HasherException {
        String ext = extension(file.getFileName().toString());
        Path tmpDir = Files.createTempDirectory(env.cacheDir(), "tmp");
        try {
            switch (ext) {
                case "png":
                case "gif":
                case "jpg":
                case "jpeg":
                    Path rasterOut = tmpDir.resolve("norm.img");
                    normalizeRaster(file, rasterOut);
                    return sha256(rasterOut);
                case "dng":
                    Path rawOut = tmpDir.resolve("norm.raw");
                    normalizeRaster(file, rawOut);
                    return sha256(rawOut);
                case "mp4":
                case "mov":
                    Path videoOut = tmpDir.resolve("stream_video.bin");
                    Path audioOut = tmpDir.resolve("stream_audio.bin");
                    normalizeVideo(file, videoOut, audioOut);
                    String hv = Files.exists(videoOut) ? sha256(videoOut) : "NOVIDEO";
                    String ha = Files.exists(audioOut) ? sha256(audioOut) : "NOAUDIO";
                    if ("NOVIDEO".equals(hv) && "NOAUDIO".equals(ha)) {
                        throw new HasherException("video-no-streams", file, "no extractable streams");
                    }
                    return hv + '-' + ha;
                default:
                    throw new HasherException("unsupported-extension:" + (ext == null ? "unknown" : ext), file, "unsupported file type");
            }
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    private void normalizeRaster(Path input, Path output) throws IOException, HasherException {
        List<String> errors = new ArrayList<>();
        try {
            ProcessResult exif = ProcessUtils.run(List.of("exiftool", "-q", "-q", "-all=", "-o", output.toString(), input.toString()));
            if (exif.exitCode() == 0) {
                return;
            }
            errors.add("exiftool:" + env.formatDebugPayload(exif.stderr()));
        } catch (HasherException ex) {
            errors.add("exiftool:" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HasherException("normalize-raster-failed", input, "interrupted");
        }
        String magick = CommandLocator.firstAvailable("magick", "convert");
        if (magick != null) {
            String fmt = sniffFormat(input);
            if (fmt != null) {
                try {
                    ProcessResult magickResult = ProcessUtils.run(buildMagickCommand(magick, fmt + ':' + output, input));
                    if (magickResult.exitCode() == 0) {
                        return;
                    }
                    errors.add(magick + ':' + fmt + ':' + env.formatDebugPayload(magickResult.stderr()));
                } catch (HasherException ex) {
                    errors.add(magick + ':' + fmt + ':' + ex.getMessage());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new HasherException("normalize-raster-failed", input, "interrupted");
                }
            }
            try {
                ProcessResult fallback = ProcessUtils.run(buildMagickCommand(magick, "png:" + output, input));
                if (fallback.exitCode() == 0) {
                    return;
                }
                errors.add(magick + ":png:" + env.formatDebugPayload(fallback.stderr()));
            } catch (HasherException ex) {
                errors.add(magick + ":png:" + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new HasherException("normalize-raster-failed", input, "interrupted");
            }
        }
        throw new HasherException("normalize-raster-failed", input, String.join(" | ", errors));
    }

    private static List<String> buildMagickCommand(String magick, String target, Path input) {
        List<String> cmd = new ArrayList<>();
        cmd.add(magick);
        cmd.add(input.toString() + "[0]");
        cmd.add("-strip");
        cmd.add(target);
        return cmd;
    }

    private String sniffFormat(Path input) {
        List<List<String>> identifyVariants = new ArrayList<>();
        if (CommandLocator.isOnPath("magick")) {
            identifyVariants.add(List.of("magick", "identify", "-quiet", "-format", "%m", input.toString() + "[0]"));
        }
        if (CommandLocator.isOnPath("identify")) {
            identifyVariants.add(List.of("identify", "-quiet", "-format", "%m", input.toString() + "[0]"));
        }
        for (List<String> cmd : identifyVariants) {
            try {
                ProcessResult result = ProcessUtils.run(cmd);
                if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                    return result.stdout().trim().toLowerCase(Locale.ROOT);
                }
            } catch (InterruptedException | HasherException ignored) {
                // try next option
            }
        }
        return null;
    }

    private void normalizeVideo(Path input, Path videoOut, Path audioOut) throws IOException, HasherException {
        List<String> errors = new ArrayList<>();
        try {
            ProcessResult video = ProcessUtils.run(List.of("ffmpeg", "-y", "-v", "error", "-i", input.toString(), "-map", "0:v:0", "-c", "copy", "-f", "data", videoOut.toString()));
            if (video.exitCode() != 0) {
                errors.add("ffmpeg-video:" + env.formatDebugPayload(video.stderr()));
                Files.deleteIfExists(videoOut);
            }
        } catch (HasherException ex) {
            errors.add("ffmpeg-video:" + ex.getMessage());
            Files.deleteIfExists(videoOut);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HasherException("video-normalization-failed", input, "interrupted");
        }
        try {
            ProcessResult audio = ProcessUtils.run(List.of("ffmpeg", "-y", "-v", "error", "-i", input.toString(), "-map", "0:a:0", "-c", "copy", "-f", "data", audioOut.toString()));
            if (audio.exitCode() != 0) {
                errors.add("ffmpeg-audio:" + env.formatDebugPayload(audio.stderr()));
                Files.deleteIfExists(audioOut);
            }
        } catch (HasherException ex) {
            errors.add("ffmpeg-audio:" + ex.getMessage());
            Files.deleteIfExists(audioOut);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HasherException("video-normalization-failed", input, "interrupted");
        }
        if (!errors.isEmpty() && !Files.exists(videoOut) && !Files.exists(audioOut)) {
            throw new HasherException("video-normalization-failed", input, String.join(" | ", errors));
        }
    }

    private static String sha256(Path file) throws IOException, HasherException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new HasherException("hashing-error", file, ex.getMessage());
        }
    }

    private static void deleteRecursive(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private static String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx == -1 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
