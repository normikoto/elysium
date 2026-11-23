package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.InvalidCustomEmojiException;
import dev.biribiri.elysium.text.Shortcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports emoji packs as this server's custom emoji, and exports them as one. See {@link EmojiPackReader} for what can be
 * imported, and {@link EmojiPackWriter} for what's exported.
 *
 * <p>Each emoji is added on its own, so one that can't be used doesn't stop the rest, and a pack that stops partway can
 * be imported again, since emoji that were already added are skipped.
 */
@Service
class EmojiPacks {

    private static final Logger log = LoggerFactory.getLogger(EmojiPacks.class);

    private final CustomEmojis emojis;
    private final InstanceProperties instance;
    private final JsonMapper json;

    EmojiPacks(CustomEmojis emojis, InstanceProperties instance, JsonMapper json) {
        this.emojis = emojis;
        this.instance = instance;
        this.json = json;
    }

    /**
     * What an import did, by shortcode.
     *
     * @param skipped those that already existed, and weren't replaced
     */
    record ImportResult(List<String> added, List<String> replaced, List<String> skipped,
                        List<EmojiPackReader.Problem> failed) {
    }

    /**
     * @param archiveName      the name the archive was uploaded as, which names an Akkoma pack's category
     * @param categoryOverride null or blank to keep the pack's categories
     * @param replace          whether an emoji that already exists gets the pack's image and category, rather than being
     *                         skipped
     * @param limited          whether the admin page's limit on files applies, which the command line's imports don't have
     * @throws InvalidCustomEmojiException if the archive can't be read, the category isn't valid, or it has too many files
     */
    ImportResult importPack(Path archive, String archiveName, String categoryOverride, boolean replace, boolean limited)
            throws IOException {
        var override = CustomEmojis.checkCategory(categoryOverride);
        var limit = emojis.maxImageSize().toBytes();
        var maxFiles = limited ? emojis.maxPackFiles() : Integer.MAX_VALUE;
        try (var pack = EmojiPackReader.open(archive, archiveName, limit, maxFiles, json)) {
            var packCategory = packCategory(pack.name());
            // As addLocal reads them, so that one written as ":blob:" is found as blob.
            var shortcodes = pack.entries().stream()
                    .map(entry -> Shortcodes.parse(entry.shortcode().strip()).orElse(entry.shortcode()))
                    .toList();
            var existing = emojis.findLocal(shortcodes).keySet();
            var added = new ArrayList<String>();
            var replaced = new ArrayList<String>();
            var skipped = new ArrayList<String>();
            var failed = new ArrayList<>(pack.problems());
            for (var i = 0; i < shortcodes.size(); i++) {
                var entry = pack.entries().get(i);
                var shortcode = shortcodes.get(i);
                var category = override != null ? override
                        : pack.hasCategories() ? packCategory(entry.category()) : packCategory;
                try {
                    var exists = existing.contains(shortcode);
                    if (exists && !replace) {
                        skipped.add(shortcode);
                    } else if (exists && emojis.replaceLocal(shortcode, pack.read(entry, limit), category)) {
                        replaced.add(shortcode);
                    } else {
                        emojis.addLocal(shortcode, pack.read(entry, limit), category);
                        added.add(shortcode);
                    }
                } catch (InvalidCustomEmojiException e) {
                    failed.add(new EmojiPackReader.Problem(entry.shortcode(), e.getMessage()));
                }
            }
            return new ImportResult(added, replaced, skipped, failed);
        }
    }

    /**
     * A pack's name, or a Misskey emoji's category, only becomes a category if it can be one, so that a pack called
     * "Uncategorized", or a category longer than this server allows, is still imported.
     */
    private static String packCategory(String name) {
        try {
            return CustomEmojis.checkCategory(name);
        } catch (InvalidCustomEmojiException e) {
            return null;
        }
    }

    /**
     * Writes every local emoji, including those hidden from pickers, since neither Misskey nor Akkoma can hide one.
     */
    void export(OutputStream out) throws IOException {
        EmojiPackWriter.write(emojis.listLocal(), this::image, instance.authority(), instance.baseUrl().toString(), json,
                out);
    }

    /**
     * The name of an export's file, after this server.
     */
    String exportName() {
        return instance.authority().replaceAll("[^A-Za-z0-9.-]", "_") + "-emojis.zip";
    }

    /**
     * An image that's missing from the media directory, such as one lost from a backup, leaves its emoji out of the export
     * rather than stopping it.
     */
    private byte[] image(CustomEmoji emoji) {
        try {
            return emojis.readImage(emoji);
        } catch (UncheckedIOException | IllegalArgumentException e) {
            log.warn("Leaving :{}: out of the export, since its image can't be read", emoji.shortcode(), e);
            return null;
        }
    }
}
