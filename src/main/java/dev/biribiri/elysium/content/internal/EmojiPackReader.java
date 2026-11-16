package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.InvalidCustomEmojiException;
import dev.biribiri.elysium.media.Images;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.unit.DataSize;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads the emoji packs that other servers share: Misskey's zip with a {@code meta.json}, a Pleroma or Akkoma pack with a
 * {@code pack.json}, or a zip or {@code .tar.gz} of images, as Mastodon's {@code tootctl emoji import} takes, named after
 * their files.
 *
 * <p>Everything in an archive is untrusted. Names that would reach outside it are refused, links are ignored, and no file
 * is read past the limit of what it could be, so a small archive can't expand into a large one in memory.
 */
final class EmojiPackReader {

    static final int MAX_MANIFEST_SIZE = 4 * 1024 * 1024;

    static final String MISSKEY_MANIFEST = "meta.json";
    static final String PLEROMA_MANIFEST = "pack.json";

    private static final Pattern IMAGE = Pattern.compile("(?i).+\\.(png|gif|webp|jpe?g)");
    private static final Pattern ARCHIVE_SUFFIX = Pattern.compile("(?i)(\\.tar\\.gz|\\.tgz|\\.tar|\\.zip)$");

    private EmojiPackReader() {
    }

    /**
     * @param category what Misskey's export groups it under, or null. Other packs' emoji are grouped by the pack's name
     * @param file     its image's name in the pack
     */
    record Entry(String shortcode, String category, String file) {
    }

    /**
     * Something in the pack that can't be imported, which doesn't stop the rest.
     *
     * @param what a shortcode, or a file's name when there's no shortcode
     */
    record Problem(String what, String reason) {
    }

    /**
     * An opened pack, whose extracted files are removed when it's closed.
     *
     * @param name                what its emoji are grouped under, unless it has categories of its own: the folder
     *                            it's in, or the archive's name, or null
     * @param hasCategories       whether it's Misskey's, which gives each emoji's category, including none
     */
    record Pack(String name, boolean hasCategories, List<Entry> entries, List<Problem> problems, Source source)
            implements Closeable {

        /**
         * @throws InvalidCustomEmojiException if it isn't in the pack, or is larger than the limit
         */
        byte[] read(Entry entry, long limit) {
            return source.read(entry.file(), limit);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    /**
     * @param archiveName the name it was uploaded as, for the pack's name
     * @param maxFileSize the most any file could need to be read, past which it's refused rather than read
     * @param maxFiles    the most files it can have, emoji or not. A tar archive is refused as soon as it passes this,
     *                    so a small compressed one can't fill the disk before it's counted
     * @throws InvalidCustomEmojiException if it isn't an archive this can read, its manifest isn't valid, or it has too
     *                                     many files
     */
    static Pack open(Path archive, String archiveName, long maxFileSize, int maxFiles, JsonMapper json)
            throws IOException {
        var source = source(archive, Math.max(maxFileSize, MAX_MANIFEST_SIZE), maxFiles);
        try {
            if (source.files().size() > maxFiles) {
                throw tooManyFiles(maxFiles);
            }
            return read(source, archiveName, json);
        } catch (RuntimeException e) {
            source.close();
            throw e;
        }
    }

    private static Pack read(Source source, String archiveName, JsonMapper json) {
        var root = root(source.files());
        var name = root.isEmpty() ? stem(archiveName) : root.substring(0, root.length() - 1);
        var misskey = source.manifest(root + MISSKEY_MANIFEST);
        var pleroma = source.manifest(root + PLEROMA_MANIFEST);
        var problems = new ArrayList<Problem>();
        List<Entry> entries;
        if (misskey.isPresent()) {
            entries = misskey(parse(misskey.get(), MISSKEY_MANIFEST, json), root, problems);
        } else if (pleroma.isPresent()) {
            entries = pleroma(parse(pleroma.get(), PLEROMA_MANIFEST, json), root, problems);
        } else {
            entries = images(source.files());
        }
        return new Pack(name, misskey.isPresent(), unique(entries, problems), problems, source);
    }

    /**
     * Archives are often made by compressing a folder, which puts everything in it under the folder's name.
     *
     * @return the folder everything is in, with a slash, or empty if they aren't all in one
     */
    private static String root(Set<String> files) {
        String folder = null;
        for (var file : files) {
            var slash = file.indexOf('/');
            if (slash < 0) {
                return "";
            }
            var first = file.substring(0, slash + 1);
            if (folder == null) {
                folder = first;
            } else if (!folder.equals(first)) {
                return "";
            }
        }
        return folder == null ? "" : folder;
    }

    private static String stem(String archiveName) {
        if (archiveName == null) {
            return null;
        }
        var base = archiveName.substring(Math.max(archiveName.lastIndexOf('/'), archiveName.lastIndexOf('\\')) + 1);
        var stem = ARCHIVE_SUFFIX.matcher(base).replaceFirst("").strip();
        return stem.isEmpty() ? null : stem;
    }

    private static JsonNode parse(byte[] manifest, String file, JsonMapper json) {
        JsonNode node;
        try {
            node = json.readTree(manifest);
        } catch (JacksonException e) {
            throw new InvalidCustomEmojiException("The pack's %s isn't valid JSON".formatted(file));
        }
        if (node == null || !node.isObject()) {
            throw new InvalidCustomEmojiException("The pack's %s isn't a JSON object".formatted(file));
        }
        return node;
    }

    /**
     * Misskey's export lists each emoji with its file and category. It leaves out the image of one it couldn't download,
     * and marks it so, as its own import skips them.
     */
    private static List<Entry> misskey(JsonNode manifest, String root, List<Problem> problems) {
        var emojis = manifest.path("emojis");
        if (!emojis.isArray()) {
            throw new InvalidCustomEmojiException("The pack's meta.json has no list of emojis");
        }
        var entries = new ArrayList<Entry>();
        for (var record : emojis) {
            var emoji = record.path("emoji");
            var shortcode = string(emoji.path("name"));
            var file = string(record.path("fileName"));
            var what = shortcode != null ? shortcode : file != null ? file : "An emoji";
            if (record.path("downloaded").isBoolean() && !record.path("downloaded").booleanValue()) {
                problems.add(new Problem(what, "Its server couldn't download its image when it was exported"));
            } else if (shortcode == null || file == null) {
                problems.add(new Problem(what, "meta.json doesn't give its name and file"));
            } else {
                entries.add(new Entry(shortcode, string(emoji.path("category")), root + file));
            }
        }
        return entries;
    }

    /**
     * Akkoma's packs map shortcodes to files, which can be in folders, and group every emoji under the pack's name.
     */
    private static List<Entry> pleroma(JsonNode manifest, String root, List<Problem> problems) {
        var files = manifest.path("files");
        if (!files.isObject()) {
            throw new InvalidCustomEmojiException("The pack's pack.json has no files");
        }
        var entries = new ArrayList<Entry>();
        for (var file : files.properties()) {
            var path = string(file.getValue());
            if (path == null) {
                problems.add(new Problem(file.getKey(), "pack.json doesn't give its file"));
            } else {
                entries.add(new Entry(file.getKey(), null, root + path));
            }
        }
        return entries;
    }

    private static List<Entry> images(Set<String> files) {
        var entries = new ArrayList<Entry>();
        for (var file : files) {
            var base = file.substring(file.lastIndexOf('/') + 1);
            if (IMAGE.matcher(base).matches()) {
                entries.add(new Entry(base.substring(0, base.lastIndexOf('.')), null, file));
            }
        }
        entries.sort((a, b) -> a.shortcode().compareTo(b.shortcode()));
        return entries;
    }

    /**
     * A second emoji with the same shortcode would replace the first when replacing is chosen, so only the first counts.
     */
    private static List<Entry> unique(List<Entry> entries, List<Problem> problems) {
        var seen = new HashSet<String>();
        var unique = new ArrayList<Entry>();
        for (var entry : entries) {
            if (seen.add(entry.shortcode())) {
                unique.add(entry);
            } else {
                problems.add(new Problem(entry.shortcode(), "The pack has more than one emoji with this shortcode"));
            }
        }
        return unique;
    }

    private static String string(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        var value = node.stringValue().strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * @return the name as a relative path with forward slashes, or empty for one that reaches outside the archive, a
     * folder, or a file that macOS adds alongside the real ones
     */
    static Optional<String> normalize(String name) {
        if (name == null || name.isEmpty() || name.endsWith("/") || name.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        var parts = new ArrayList<String>();
        var segments = name.replace('\\', '/').split("/", -1);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (segment.isEmpty()) {
                // A leading slash is an absolute path. Others are doubled slashes, which are harmless.
                if (i == 0) {
                    return Optional.empty();
                }
                continue;
            }
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..") || segment.contains(":") || segment.startsWith("._")
                    || segment.equals("__MACOSX")) {
                return Optional.empty();
            }
            parts.add(segment);
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join("/", parts));
    }

    private static Source source(Path archive, long maxFileSize, int maxFiles) throws IOException {
        byte[] magic;
        try (var in = Files.newInputStream(archive)) {
            magic = in.readNBytes(262);
        }
        if (magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K' && (magic[2] == 3 || magic[2] == 5)) {
            return ZipSource.open(archive);
        }
        if (magic.length >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b) {
            return TarSource.extract(archive, true, maxFileSize, maxFiles);
        }
        if (magic.length >= 262 && new String(magic, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) {
            return TarSource.extract(archive, false, maxFileSize, maxFiles);
        }
        throw new InvalidCustomEmojiException("A pack has to be a .zip, .tar.gz, or .tar file");
    }

    /**
     * The files in an archive, by their normalized names.
     */
    interface Source extends Closeable {

        Set<String> files();

        /**
         * @throws InvalidCustomEmojiException if it's larger than a manifest can be
         */
        default Optional<byte[]> manifest(String file) {
            return files().contains(file) ? Optional.of(read(file, MAX_MANIFEST_SIZE)) : Optional.empty();
        }

        /**
         * @param file a name as a manifest gives it, which is normalized
         * @throws InvalidCustomEmojiException if it isn't in the archive, or is larger than the limit
         */
        byte[] read(String file, long limit);
    }

    static InvalidCustomEmojiException missing(String file) {
        return new InvalidCustomEmojiException("The pack has no file called " + file);
    }

    static InvalidCustomEmojiException tooManyFiles(int maxFiles) {
        return new InvalidCustomEmojiException(
                "A pack can't have more than %d files. Import larger ones from the command line".formatted(maxFiles));
    }

    static InvalidCustomEmojiException tooLarge(String file, long limit) {
        return new InvalidCustomEmojiException(
                "%s is larger than %s".formatted(file, Images.describe(DataSize.ofBytes(limit))));
    }

    /**
     * Reads at most one byte past the limit, whatever the archive claims a file's size is.
     */
    static byte[] readLimited(InputStream in, String file, long limit) throws IOException {
        var bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
        if (bytes.length > limit) {
            throw tooLarge(file, limit);
        }
        return bytes;
    }

    private static final class ZipSource implements Source {

        private final ZipFile zip;
        private final Map<String, String> entries;

        private ZipSource(ZipFile zip, Map<String, String> entries) {
            this.zip = zip;
            this.entries = entries;
        }

        static ZipSource open(Path archive) throws IOException {
            ZipFile zip;
            try {
                zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
            } catch (ZipException e) {
                throw new InvalidCustomEmojiException("The pack isn't a zip file that can be read");
            }
            try {
                var entries = new LinkedHashMap<String, String>();
                var stream = zip.stream().iterator();
                while (stream.hasNext()) {
                    var entry = stream.next();
                    if (!entry.isDirectory()) {
                        normalize(entry.getName()).ifPresent(name -> entries.putIfAbsent(name, entry.getName()));
                    }
                }
                return new ZipSource(zip, entries);
            } catch (IllegalArgumentException e) {
                // ZipFile reports a name that isn't valid UTF-8 this way.
                zip.close();
                throw new InvalidCustomEmojiException("The pack isn't a zip file that can be read");
            } catch (RuntimeException e) {
                zip.close();
                throw e;
            }
        }

        @Override
        public Set<String> files() {
            return entries.keySet();
        }

        @Override
        public byte[] read(String file, long limit) {
            var name = normalize(file).map(entries::get).orElseThrow(() -> missing(file));
            try (var in = zip.getInputStream(zip.getEntry(name))) {
                return readLimited(in, file, limit);
            } catch (ZipException e) {
                throw new InvalidCustomEmojiException("%s couldn't be read from the pack".formatted(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    /**
     * A tar archive can only be read from start to end, so its files are written to a temporary folder first. Each is
     * cut off at the limit, and only remembered as too large.
     */
    private static final class TarSource implements Source {

        private final Path directory;
        private final Map<String, Path> files;
        private final Set<String> tooLarge;
        private final Set<String> all;

        private TarSource(Path directory, Map<String, Path> files, Set<String> tooLarge) {
            this.directory = directory;
            this.files = files;
            this.tooLarge = tooLarge;
            var all = new LinkedHashSet<>(files.keySet());
            all.addAll(tooLarge);
            this.all = all;
        }

        static TarSource extract(Path archive, boolean gzipped, long maxFileSize, int maxFiles) throws IOException {
            var directory = Files.createTempDirectory("elysium-emoji-pack-");
            var files = new LinkedHashMap<String, Path>();
            var tooLarge = new HashSet<String>();
            try (var raw = new BufferedInputStream(Files.newInputStream(archive));
                 var decompressed = gzipped ? new GZIPInputStream(raw) : raw;
                 var tar = new TarArchiveInputStream(decompressed)) {
                for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                    if (!entry.isFile()) {
                        continue;
                    }
                    var name = normalize(entry.getName());
                    if (name.isEmpty() || files.containsKey(name.get()) || tooLarge.contains(name.get())) {
                        continue;
                    }
                    if (files.size() + tooLarge.size() >= maxFiles) {
                        throw tooManyFiles(maxFiles);
                    }
                    var bytes = tar.readNBytes((int) Math.min(maxFileSize + 1, Integer.MAX_VALUE - 8));
                    if (bytes.length > maxFileSize) {
                        tooLarge.add(name.get());
                        continue;
                    }
                    // Numbered rather than named after the entry, so no name from the archive becomes a path.
                    var path = directory.resolve(Integer.toString(files.size()));
                    write(path, bytes);
                    files.put(name.get(), path);
                }
            } catch (IOException | IllegalArgumentException e) {
                // Only reading the archive throws these: writing the files throws UncheckedIOException.
                FileSystemUtils.deleteRecursively(directory);
                throw new InvalidCustomEmojiException("The pack isn't a tar file that can be read");
            } catch (RuntimeException e) {
                FileSystemUtils.deleteRecursively(directory);
                throw e;
            }
            return new TarSource(directory, files, tooLarge);
        }

        private static void write(Path path, byte[] bytes) {
            try {
                Files.write(path, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Set<String> files() {
            return all;
        }

        @Override
        public byte[] read(String file, long limit) {
            var name = normalize(file).orElseThrow(() -> missing(file));
            if (tooLarge.contains(name)) {
                throw tooLarge(file, limit);
            }
            var path = files.get(name);
            if (path == null) {
                throw missing(file);
            }
            try (var in = Files.newInputStream(path)) {
                return readLimited(in, file, limit);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            FileSystemUtils.deleteRecursively(directory);
        }
    }
}
