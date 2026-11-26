package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.FeaturedTagRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.UUID;

/**
 * Hashtags accounts feature on their profiles, by their normalized names, with how much each account has used them. A
 * remote account's are what its server last listed, and how much it has used them is counted from its statuses stored
 * here, as on Mastodon.
 */
@Service
public class FeaturedTags {

    /**
     * As on Mastodon, whose clients assume it.
     */
    public static final int MAX_PER_ACCOUNT = 10;

    /**
     * As on Mastodon.
     */
    public static final int MAX_SUGGESTIONS = 10;

    private final FeaturedTagRepository tags;
    private final ApplicationEventPublisher events;

    FeaturedTags(FeaturedTagRepository tags, ApplicationEventPublisher events) {
        this.tags = tags;
        this.events = events;
    }

    /**
     * Featuring a tag already featured changes nothing, and returns it, as on Mastodon. The limit's message is
     * Mastodon's. Only for local accounts, since it's sent to other servers.
     *
     * @param name a normalized name
     * @throws InvalidFeaturedTagException if the account has featured {@value #MAX_PER_ACCOUNT} other tags already
     */
    @Transactional
    public FeaturedTag feature(UUID accountId, String name) {
        tags.lockAccount(accountId);
        return tags.find(accountId, name).orElseGet(() -> {
            if (tags.count(accountId) >= MAX_PER_ACCOUNT) {
                throw new InvalidFeaturedTagException("You have already featured the maximum number of hashtags");
            }
            tags.insert(accountId, name);
            var featured = tags.find(accountId, name).orElseThrow();
            events.publishEvent(new TagFeatured(featured.id(), accountId));
            return featured;
        });
    }

    /**
     * @return whether the account had featured the tag with this ID
     */
    @Transactional
    public boolean unfeature(UUID accountId, UUID id) {
        var name = tags.delete(accountId, id);
        name.ifPresent(deleted -> events.publishEvent(new TagUnfeatured(id, accountId, deleted)));
        return name.isPresent();
    }

    /**
     * @return the tag, if the account still features it
     */
    public Optional<FeaturedTag> find(UUID accountId, UUID id) {
        return tags.findById(accountId, id);
    }

    /**
     * The account's featured tags, most used first.
     */
    public List<FeaturedTag> featured(UUID accountId) {
        return tags.findAll(accountId);
    }

    /**
     * @return which of these normalized names the account features
     */
    public Set<String> featured(UUID accountId, Collection<String> names) {
        return tags.findNames(accountId, names);
    }

    /**
     * Up to {@value #MAX_SUGGESTIONS} tags the account has used recently in statuses anyone can see, and hasn't featured,
     * the most recently used first.
     */
    public List<String> suggestions(UUID accountId) {
        return tags.findSuggestions(accountId, MAX_SUGGESTIONS);
    }

    /**
     * Replaces what a remote account features, keeping the IDs of the tags it still does. Only the first
     * {@value #MAX_PER_ACCOUNT} are kept, as on Mastodon.
     *
     * @param names normalized names, in the order its server listed them
     */
    @Transactional
    public void replaceRemote(UUID accountId, SequencedCollection<String> names) {
        var kept = names.stream().distinct().limit(MAX_PER_ACCOUNT).toList();
        tags.lockAccount(accountId);
        tags.deleteExcept(accountId, kept);
        kept.forEach(name -> tags.insert(accountId, name));
    }

    void removeForAccount(UUID accountId) {
        tags.deleteForAccount(accountId);
    }

    /**
     * In batches, as pins are removed, so that a large server isn't one long delete.
     */
    void removeForDomain(String domain) {
        int deleted;
        do {
            deleted = tags.deleteForDomain(domain, 1000);
        } while (deleted == 1000);
    }
}
