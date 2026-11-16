package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.internal.CustomEmojiRepository;
import dev.biribiri.elysium.content.internal.ReactionRepository;
import dev.biribiri.elysium.media.ImageKind;
import dev.biribiri.elysium.media.Images;
import dev.biribiri.elysium.media.InvalidImageException;
import dev.biribiri.elysium.media.StoredImage;
import dev.biribiri.elysium.text.Shortcodes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Custom emoji: this server's own, which admins add, and other servers', which are stored as their statuses use them.
 *
 * <p>Images are kept as attachments are: a file is stored before its row is, and removed after its row is, through
 * {@code media_file_deletion}.
 */
@Service
public class CustomEmojis {

    public static final int MAX_CATEGORY_LENGTH = 50;

    /**
     * What the admin page calls the emoji with no category. A category can't have this name, in any case, or the page
     * would show two groups that look the same.
     */
    public static final String UNCATEGORIZED = "Uncategorized";

    /**
     * Another server's emoji is kept this long after any status last used it, so that one being stored doesn't lose it
     * in between, and one that's used again soon isn't downloaded again.
     */
    private static final Duration UNUSED_RETENTION = Duration.ofDays(1);

    private final CustomEmojiRepository emojis;
    private final Images images;
    private final MediaAttachments attachments;
    private final InstanceProperties instance;
    private final ApplicationEventPublisher events;
    private final CustomEmojiProperties properties;
    private final AttachmentProperties attachmentProperties;
    private final ReactionRepository reactions;
    private final TransactionTemplate transaction;

    CustomEmojis(CustomEmojiRepository emojis, Images images, MediaAttachments attachments, InstanceProperties instance,
                 ApplicationEventPublisher events, CustomEmojiProperties properties,
                 AttachmentProperties attachmentProperties, ReactionRepository reactions,
                 PlatformTransactionManager transactionManager) {
        this.reactions = reactions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.attachmentProperties = attachmentProperties;
        this.emojis = emojis;
        this.images = images;
        this.attachments = attachments;
        this.instance = instance;
        this.events = events;
    }

    /**
     * Checks an image's size before it's read, with the message {@link #addLocal} would give.
     *
     * @throws InvalidCustomEmojiException if it's too large
     */
    public void checkImageSize(long size) {
        try {
            Images.checkSize(size, properties.maxImageSize());
        } catch (InvalidImageException e) {
            throw new InvalidCustomEmojiException(e.getMessage());
        }
    }

    /**
     * @param category null or blank for none
     * @throws InvalidCustomEmojiException if the shortcode isn't valid or is taken, or the image can't be used
     */
    public CustomEmoji addLocal(String shortcode, byte[] image, String category) {
        var name = Shortcodes.parse(shortcode == null ? null : shortcode.strip()).orElseThrow(() ->
                new InvalidCustomEmojiException("A shortcode has 2 to %d letters, digits, or underscores"
                        .formatted(Shortcodes.MAX_LENGTH)));
        var checkedCategory = category(category);
        if (!emojis.findLocal(List.of(name)).isEmpty()) {
            throw taken(name);
        }
        StoredImage stored;
        try {
            stored = images.store(image, ImageKind.EMOJI, properties.maxImageSize());
        } catch (InvalidImageException e) {
            throw new InvalidCustomEmojiException(e.getMessage());
        }
        var id = Ids.timeOrdered(Instant.now());
        var emoji = new CustomEmoji(id, name, null, instance.resolve("/emojis/" + id), stored.name(),
                stored.staticName(), null, checkedCategory, true);
        boolean inserted;
        try {
            inserted = emojis.insertLocal(emoji);
        } catch (RuntimeException e) {
            images.delete(stored);
            throw e;
        }
        if (!inserted) {
            images.delete(stored);
            throw taken(name);
        }
        return emoji;
    }

    /**
     * The largest image an emoji can have, as admins are told it.
     */
    public String describeMaxImageSize() {
        return Images.describe(properties.maxImageSize());
    }

    /**
     * The largest image of another server's emoji that's downloaded.
     */
    public DataSize maxImageSize() {
        return properties.maxImageSize();
    }

    private static InvalidCustomEmojiException taken(String name) {
        return new InvalidCustomEmojiException("There's already an emoji called :%s:".formatted(name));
    }

    /**
     * @return false if there's no local emoji with the ID
     * @throws InvalidCustomEmojiException if the category is too long
     */
    public boolean updateLocal(UUID id, String category, boolean visibleInPicker) {
        return emojis.updateLocal(id, category(category), visibleInPicker);
    }

    /**
     * Moves every local emoji in a category to another, which merges them if it's one that's already used.
     *
     * @param from the category as it's stored
     * @param to   null or blank to leave them uncategorized
     * @return how many were moved
     * @throws InvalidCustomEmojiException if the new category isn't valid
     */
    public int renameLocalCategory(String from, String to) {
        var checked = category(to);
        if (from == null || from.isBlank() || from.equals(checked)) {
            return 0;
        }
        return emojis.renameLocalCategory(from, checked);
    }

    /**
     * IDs that aren't of a local emoji, such as one removed meanwhile, are skipped.
     *
     * @param category null or blank to leave them uncategorized
     * @return how many were moved
     * @throws InvalidCustomEmojiException if the category isn't valid
     */
    public int moveLocal(Collection<UUID> ids, String category) {
        return emojis.updateLocalCategory(ids, category(category));
    }

    /**
     * Statuses that used it show its shortcode as text from then on. Local accounts' reactions with it are withdrawn,
     * which other servers are sent.
     *
     * @return false if there's no local emoji with the ID
     */
    public boolean deleteLocal(UUID id) {
        var deleted = Boolean.TRUE.equals(transaction.execute(tx -> {
            if (!emojis.lockLocal(id)) {
                return false;
            }
            withdrawReactions(List.of(id));
            return emojis.deleteLocal(id);
        }));
        if (deleted) {
            attachments.processDeletions();
        }
        return deleted;
    }

    /**
     * The emoji are held, so that no reaction can be added with them meanwhile, and are removed in the same transaction.
     */
    private void withdrawReactions(List<UUID> emojiIds) {
        reactions.withdrawWith(emojiIds).forEach(withdrawn -> events.publishEvent(new StatusUnreacted(
                withdrawn.actorId(), withdrawn.statusId(), withdrawn.authorId(), withdrawn.statusUri(),
                withdrawn.activityUri(), withdrawn.name(), withdrawn.emojiId())));
    }

    public List<CustomEmoji> listLocal() {
        return emojis.findAllLocal();
    }

    public Optional<CustomEmoji> find(UUID id) {
        return emojis.findById(id);
    }

    public Optional<CustomEmoji> findLocal(UUID id) {
        return emojis.findById(id).filter(CustomEmoji::isLocal);
    }

    /**
     * This server's emoji that some texts name, as a status or profile that has them would show them.
     *
     * @return them by shortcode
     */
    public Map<String, CustomEmoji> findLocalIn(Collection<String> texts) {
        return findLocal(Shortcodes.names(texts));
    }

    /**
     * @param shortcodes names without colons
     * @return this server's emoji with those names, by shortcode
     */
    public Map<String, CustomEmoji> findLocal(Collection<String> shortcodes) {
        var found = new LinkedHashMap<String, CustomEmoji>();
        emojis.findLocal(shortcodes).forEach(emoji -> found.put(emoji.shortcode(), emoji));
        return found;
    }

    public List<CustomEmoji> findAll(Collection<UUID> ids) {
        return emojis.findByIds(ids);
    }

    /**
     * @return each status's emoji, for each status that has any
     */
    public Map<UUID, List<CustomEmoji>> findForStatuses(Collection<UUID> statusIds) {
        return emojis.findByStatuses(statusIds);
    }

    public List<CustomEmoji> findForStatus(UUID statusId) {
        return emojis.findByStatuses(List.of(statusId)).getOrDefault(statusId, List.of());
    }

    /**
     * @return the emoji that each account on another server's profile lists, for each that has any. A local profile's are
     * found from its text, with {@link #findLocalIn}.
     */
    public Map<UUID, List<CustomEmoji>> findForRemoteProfiles(Collection<UUID> actorIds) {
        return emojis.findByProfiles(actorIds);
    }

    /**
     * Replaces the emoji that an account on another server's profile lists, with those {@link #storeRemote} stored for it,
     * in the same transaction. Those listed keep their emoji from being removed as unused.
     */
    public void replaceForRemoteProfile(UUID actorId, Collection<UUID> emojiIds) {
        emojis.replaceForProfile(actorId, emojiIds);
    }

    void insertForStatus(UUID statusId, Collection<UUID> emojiIds) {
        emojis.insertForStatus(statusId, emojiIds);
    }

    void replaceForStatus(UUID statusId, Collection<UUID> emojiIds) {
        emojis.replaceForStatus(statusId, emojiIds);
    }

    /**
     * Stores the emoji that another server's status or activity uses, under the domain of the actor that used them, so
     * that a server can't change another's. Those that have no copy yet are downloaded, which includes one whose image
     * changed, and one that couldn't be downloaded for a while, such as when its server had used up its budget. Has to
     * run in the transaction that stores what uses them. They're stored in order of shortcode, so that two transactions
     * storing the same emoji hold them in the same order, and can't deadlock.
     *
     * @param domain the actor's domain, as actors have it
     * @return their IDs, by shortcode
     */
    public Map<String, UUID> storeRemote(String domain, Collection<RemoteEmoji> remote) {
        var first = new TreeMap<String, RemoteEmoji>();
        remote.forEach(emoji -> first.putIfAbsent(emoji.shortcode(), emoji));
        var ids = new LinkedHashMap<String, UUID>();
        var downloads = new ArrayList<UUID>();
        for (var emoji : first.values()) {
            var uri = emoji.uri() == null ? emoji.url() : emoji.uri();
            var stored = emojis.upsertRemote(Ids.timeOrdered(Instant.now()), emoji.shortcode(), domain, uri,
                    emoji.url(), emoji.updated());
            if (stored.needsDownload()) {
                downloads.add(stored.id());
            }
            ids.put(emoji.shortcode(), stored.id());
        }
        // With copies turned off, nothing is ever downloaded, so this would only queue events for nothing.
        if (!downloads.isEmpty() && attachmentProperties.remoteCacheEnabled()) {
            events.publishEvent(new RemoteEmojiAdded(downloads));
        }
        return ids;
    }

    /**
     * Another server's emoji that's stored here, as an account here can name it.
     */
    public Optional<CustomEmoji> findRemote(String shortcode, String domain) {
        return emojis.findRemote(shortcode, domain);
    }

    public Optional<RemoteEmojiSource> findForDownload(UUID id) {
        return emojis.findForDownload(id);
    }

    /**
     * Keeps a downloaded image of another server's emoji, re-encoded as an upload would be.
     *
     * @param url where it was downloaded from, so that an image the emoji no longer has isn't kept
     * @return whether it's kept. One that isn't an image that can be used stays a link
     */
    public boolean cacheRemote(UUID id, URI url, byte[] image) {
        StoredImage stored;
        try {
            stored = images.store(image, ImageKind.EMOJI, properties.maxImageSize());
        } catch (InvalidImageException e) {
            emojis.giveUpCaching(id);
            return false;
        }
        boolean recorded;
        try {
            recorded = emojis.cache(id, url, stored.name(), stored.staticName());
        } catch (RuntimeException e) {
            images.delete(stored);
            throw e;
        }
        if (!recorded) {
            images.delete(stored);
        }
        return recorded;
    }

    public void giveUpCaching(UUID id) {
        emojis.giveUpCaching(id);
    }

    /**
     * Removes a server's emoji and their files, for when it's blocked. Safe to stop partway, and to repeat.
     *
     * @param domain a domain as domain blocks have it, which covers its subdomains
     */
    public void removeForDomain(String domain) {
        while (Boolean.TRUE.equals(transaction.execute(tx -> {
            var batch = emojis.lockBatchForDomain(domain);
            withdrawReactions(batch);
            emojis.deleteRemote(batch);
            return !batch.isEmpty();
        }))) {
            // Each batch commits by itself.
        }
        attachments.processDeletions();
    }

    /**
     * Lets a server's emoji be downloaded again, unless another block still rejects its media, for when an admin stops
     * rejecting it. Each is downloaded the next time something uses it.
     */
    public void allowForDomain(String domain) {
        while (emojis.allowForDomain(domain) > 0) {
            // Each batch commits by itself.
        }
    }

    /**
     * Removes the copies of a server's emoji, which are linked from then on, for when an admin starts rejecting its media.
     */
    public void rejectForDomain(String domain) {
        while (emojis.rejectForDomain(domain) > 0) {
            // Each batch commits by itself.
        }
        attachments.processDeletions();
    }

    /**
     * Removes other servers' emoji that no status, reaction, or profile uses any more, and their files. Safe to stop partway, and to
     * repeat.
     */
    public void removeUnused() {
        var before = Instant.now().minus(UNUSED_RETENTION);
        while (emojis.removeUnused(before) > 0) {
            // Each batch commits by itself.
        }
        attachments.processDeletions();
    }

    private static String category(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        var stripped = category.strip();
        if (!StatusService.isStorable(stripped)) {
            throw new InvalidCustomEmojiException("The category contains invalid characters");
        }
        if (stripped.codePointCount(0, stripped.length()) > MAX_CATEGORY_LENGTH) {
            throw new InvalidCustomEmojiException(
                    "A category can't be longer than %d characters".formatted(MAX_CATEGORY_LENGTH));
        }
        if (stripped.equalsIgnoreCase(UNCATEGORIZED)) {
            throw new InvalidCustomEmojiException(
                    "\"%s\" is reserved. Leave the category empty instead".formatted(UNCATEGORIZED));
        }
        return stripped;
    }
}
