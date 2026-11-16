package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.CustomEmoji;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one zip that both Misskey and Akkoma can import, as each one's own export would be.
 *
 * <ul>
 *     <li>Misskey reads {@code meta.json}. It wants it and every image at the top of the zip, with names of only letters,
 *     digits, underscores, and dots, and skips an emoji that isn't marked as downloaded. It ignores other files, such as
 *     {@code pack.json}.</li>
 *     <li>Akkoma reads {@code pack.json} in the pack's folder, and only the files it lists, so {@code meta.json} doesn't
 *     become an emoji. It has no categories of its own, and groups every emoji under the pack's name.</li>
 * </ul>
 *
 * <p>Shortcodes already fit Misskey's rules, so each image is named after its emoji.
 */
final class EmojiPackWriter {

    private EmojiPackWriter() {
    }

    /**
     * @param images   reads each emoji's image as it's kept, which is always WebP, or gives null to leave it out
     * @param host     this server, as Misskey's export names it
     * @param homepage what Akkoma shows as where the pack comes from
     */
    static void write(List<CustomEmoji> emojis, Function<CustomEmoji, byte[]> images, String host, String homepage,
                      JsonMapper json, OutputStream out) throws IOException {
        var zip = new ZipOutputStream(out);
        var written = new ArrayList<CustomEmoji>();
        for (var emoji : emojis) {
            var image = images.apply(emoji);
            if (image == null) {
                continue;
            }
            zip.putNextEntry(new ZipEntry(fileName(emoji)));
            zip.write(image);
            zip.closeEntry();
            written.add(emoji);
        }
        zip.putNextEntry(new ZipEntry(EmojiPackReader.MISSKEY_MANIFEST));
        zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(misskey(written, host, json)));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry(EmojiPackReader.PLEROMA_MANIFEST));
        zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(pleroma(written, host, homepage, json)));
        zip.closeEntry();
        zip.finish();
    }

    static String fileName(CustomEmoji emoji) {
        return emoji.shortcode() + ".webp";
    }

    /**
     * Every field Misskey's import passes on is given, rather than left for its defaults.
     */
    private static ObjectNode misskey(List<CustomEmoji> emojis, String host, JsonMapper json) {
        var meta = json.createObjectNode();
        meta.put("metaVersion", 2);
        meta.put("host", host);
        meta.put("exportedAt", Instant.now().toString());
        var list = meta.putArray("emojis");
        for (var emoji : emojis) {
            var record = list.addObject();
            record.put("downloaded", true);
            record.put("fileName", fileName(emoji));
            var fields = record.putObject("emoji");
            fields.put("name", emoji.shortcode());
            fields.put("category", emoji.category());
            fields.putArray("aliases");
            fields.putNull("license");
            fields.put("isSensitive", false);
            fields.put("localOnly", false);
            fields.putArray("roleIdsThatCanBeUsedThisEmojiAsReaction");
            fields.put("type", "image/webp");
        }
        return meta;
    }

    private static ObjectNode pleroma(List<CustomEmoji> emojis, String host, String homepage, JsonMapper json) {
        var pack = json.createObjectNode();
        var about = pack.putObject("pack");
        about.put("description", "Custom emoji from " + host);
        about.put("homepage", homepage);
        about.putNull("license");
        about.put("share-files", true);
        var files = pack.putObject("files");
        emojis.forEach(emoji -> files.put(emoji.shortcode(), fileName(emoji)));
        pack.put("files_count", emojis.size());
        return pack;
    }
}
