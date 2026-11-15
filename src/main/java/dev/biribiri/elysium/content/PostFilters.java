package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.PostFilterRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Users' filters of statuses, which Mastodon's v2 API manages as filters holding keywords and statuses, and its v1 API as
 * one filter for each keyword.
 * <p>
 * Every method takes the account that owns the filter, and one that another account owns reads as missing. The messages
 * of what's refused are Mastodon's, where it has them.
 */
@Service
public class PostFilters {

    public static final int MAX_TITLE_LENGTH = 256;
    public static final int MAX_KEYWORD_LENGTH = 512;

    /**
     * Mastodon has no limits on these. Every status shown to a user is matched against all of their filters, so they have
     * to stop somewhere.
     */
    public static final int MAX_FILTERS = 100;
    public static final int MAX_KEYWORDS_PER_FILTER = 100;
    public static final int MAX_STATUSES_PER_FILTER = 1000;

    private final PostFilterRepository repository;
    private final StatusService statuses;
    private final ApplicationEventPublisher events;

    PostFilters(PostFilterRepository repository, StatusService statuses, ApplicationEventPublisher events) {
        this.repository = repository;
        this.statuses = statuses;
        this.events = events;
    }

    /**
     * @return the account's filters, oldest first, including expired ones
     */
    public List<PostFilter> list(UUID accountId) {
        return repository.findByAccount(accountId);
    }

    public Optional<PostFilter> find(UUID accountId, UUID filterId) {
        return repository.find(accountId, filterId);
    }

    /**
     * The account's filters that apply now, for matching statuses shown to it.
     */
    public PostFilterMatcher matcher(UUID accountId) {
        var active = repository.findActiveByAccount(accountId, Instant.now());
        return active.isEmpty() ? PostFilterMatcher.NONE : new PostFilterMatcher(active);
    }

    /**
     * @param change its title and contexts are required. Its action defaults to {@link PostFilter.Action#WARN}, and a new
     *               keyword's whole-word setting to true, as on Mastodon
     * @throws InvalidPostFilterException if the filter is refused
     */
    @Transactional
    public PostFilter create(UUID accountId, PostFilterChange change) {
        if (change.title() == null || change.title().isBlank()) throw new InvalidPostFilterException("Title can't be blank");
        if (change.contexts() == null || change.contexts().isEmpty()) {
            throw new InvalidPostFilterException("Context can't be blank");
        }
        var title = title(change.title());
        for (var edit : change.keywords()) {
            if (edit.id() != null) throw new InvalidPostFilterException("Keyword is not part of this filter");
        }
        repository.lockAccount(accountId);
        if (repository.countByAccount(accountId) >= MAX_FILTERS) {
            throw new InvalidPostFilterException("You can't have more than " + MAX_FILTERS + " filters");
        }
        var id = repository.insert(accountId, title, change.contexts(),
                change.action() == null ? PostFilter.Action.WARN : change.action(),
                change.expiry() == null ? null : change.expiry().at());
        applyKeywords(id, List.of(), change.keywords());
        changed(accountId);
        return repository.find(accountId, id).orElseThrow();
    }

    /**
     * Changes what the change sets, and adds, changes, or removes its keywords, all or none of them.
     *
     * @return the filter as changed, or empty if the account has no such filter, or it doesn't hold a keyword to change
     * @throws InvalidPostFilterException if the change is refused
     */
    @Transactional
    public Optional<PostFilter> update(UUID accountId, UUID filterId, PostFilterChange change) {
        repository.lockAccount(accountId);
        var found = repository.find(accountId, filterId);
        if (found.isEmpty()) return Optional.empty();
        var old = found.get();
        var held = old.keywords().stream().map(PostFilter.Keyword::id).collect(Collectors.toSet());
        if (change.keywords().stream().anyMatch(edit -> edit.id() != null && !held.contains(edit.id()))) {
            return Optional.empty();
        }
        if (change.contexts() != null && change.contexts().isEmpty()) {
            throw new InvalidPostFilterException("Context can't be blank");
        }
        repository.update(filterId,
                change.title() == null ? old.title() : title(change.title()),
                change.contexts() == null ? old.contexts() : change.contexts(),
                change.action() == null ? old.action() : change.action(),
                change.expiry() == null ? old.expiresAt() : change.expiry().at());
        applyKeywords(filterId, old.keywords(), change.keywords());
        changed(accountId);
        return repository.find(accountId, filterId);
    }

    /**
     * @return whether the account had the filter
     */
    @Transactional
    public boolean delete(UUID accountId, UUID filterId) {
        repository.lockAccount(accountId);
        if (repository.find(accountId, filterId).isEmpty()) return false;
        repository.delete(filterId);
        changed(accountId);
        return true;
    }

    public Optional<List<PostFilter.Keyword>> keywords(UUID accountId, UUID filterId) {
        return repository.find(accountId, filterId).map(PostFilter::keywords);
    }

    public Optional<PostFilter.Keyword> keyword(UUID accountId, UUID keywordId) {
        return repository.findFilterOfKeyword(accountId, keywordId)
                .flatMap(filterId -> repository.find(accountId, filterId))
                .flatMap(filter -> keywordIn(filter, keywordId));
    }

    /**
     * @param wholeWord null for true, as on Mastodon
     * @return the new keyword, or empty if the account has no such filter
     * @throws InvalidPostFilterException if the keyword is refused
     */
    @Transactional
    public Optional<PostFilter.Keyword> addKeyword(UUID accountId, UUID filterId, String keyword, Boolean wholeWord) {
        repository.lockAccount(accountId);
        var filter = repository.find(accountId, filterId);
        if (filter.isEmpty()) return Optional.empty();
        if (filter.get().keywords().size() >= MAX_KEYWORDS_PER_FILTER) throw tooManyKeywords();
        var created = repository.insertKeyword(filterId, keyword(keyword), wholeWord == null || wholeWord);
        changed(accountId);
        return Optional.of(created);
    }

    /**
     * @param keyword   null to keep it
     * @param wholeWord null to keep it
     * @return the keyword as changed, or empty if the account has no such keyword
     * @throws InvalidPostFilterException if the keyword is refused
     */
    @Transactional
    public Optional<PostFilter.Keyword> updateKeyword(UUID accountId, UUID keywordId, String keyword, Boolean wholeWord) {
        repository.lockAccount(accountId);
        var old = keyword(accountId, keywordId);
        if (old.isEmpty()) return Optional.empty();
        var updated = new PostFilter.Keyword(keywordId, keyword == null ? old.get().keyword() : keyword(keyword),
                wholeWord == null ? old.get().wholeWord() : wholeWord);
        repository.updateKeyword(keywordId, updated.keyword(), updated.wholeWord());
        changed(accountId);
        return Optional.of(updated);
    }

    /**
     * Leaves the filter, even with no keywords left, as on Mastodon.
     *
     * @return whether the account had the keyword
     */
    @Transactional
    public boolean deleteKeyword(UUID accountId, UUID keywordId) {
        repository.lockAccount(accountId);
        if (repository.findFilterOfKeyword(accountId, keywordId).isEmpty()) return false;
        repository.deleteKeyword(keywordId);
        changed(accountId);
        return true;
    }

    public Optional<List<PostFilter.FilteredStatus>> statuses(UUID accountId, UUID filterId) {
        return repository.find(accountId, filterId).map(PostFilter::statuses);
    }

    public Optional<PostFilter.FilteredStatus> status(UUID accountId, UUID filteredStatusId) {
        return repository.findFilterOfStatus(accountId, filteredStatusId)
                .flatMap(filterId -> repository.find(accountId, filterId))
                .flatMap(filter -> filter.statuses().stream()
                        .filter(status -> status.id().equals(filteredStatusId)).findFirst());
    }

    /**
     * @return the new entry, or empty if the account has no such filter, or can't see the status
     * @throws InvalidPostFilterException if the filter already has the status, or has as many as it can
     */
    @Transactional
    public Optional<PostFilter.FilteredStatus> addStatus(UUID accountId, UUID filterId, UUID statusId) {
        repository.lockAccount(accountId);
        var filter = repository.find(accountId, filterId);
        if (filter.isEmpty() || statuses.findVisible(statusId, Optional.of(accountId)).isEmpty()) return Optional.empty();
        if (filter.get().statuses().size() >= MAX_STATUSES_PER_FILTER) {
            throw new InvalidPostFilterException(
                    "A filter can't have more than " + MAX_STATUSES_PER_FILTER + " statuses");
        }
        var created = repository.insertStatus(filterId, statusId)
                .orElseThrow(() -> new InvalidPostFilterException("Status has already been taken"));
        changed(accountId);
        return Optional.of(created);
    }

    /**
     * @return whether the account had the entry
     */
    @Transactional
    public boolean deleteStatus(UUID accountId, UUID filteredStatusId) {
        repository.lockAccount(accountId);
        if (repository.findFilterOfStatus(accountId, filteredStatusId).isEmpty()) return false;
        repository.deleteStatus(filteredStatusId);
        changed(accountId);
        return true;
    }

    /**
     * A keyword as the v1 API knows it, which is a filter of its own there.
     */
    public record LegacyFilter(PostFilter filter, PostFilter.Keyword keyword) {
    }

    /**
     * @return every keyword of the account's filters, in the order of their filters
     */
    public List<LegacyFilter> legacyList(UUID accountId) {
        return repository.findByAccount(accountId).stream()
                .flatMap(filter -> filter.keywords().stream().map(keyword -> new LegacyFilter(filter, keyword)))
                .toList();
    }

    public Optional<LegacyFilter> legacyFind(UUID accountId, UUID keywordId) {
        return repository.findFilterOfKeyword(accountId, keywordId)
                .flatMap(filterId -> repository.find(accountId, filterId))
                .flatMap(filter -> keywordIn(filter, keywordId).map(keyword -> new LegacyFilter(filter, keyword)));
    }

    /**
     * Makes a filter named after its phrase, with the phrase as its only keyword, as Mastodon does.
     *
     * @throws InvalidPostFilterException if the filter is refused
     */
    @Transactional
    public LegacyFilter legacyCreate(UUID accountId, String phrase, Set<PostFilter.Context> contexts,
                                     PostFilter.Action action, PostFilterChange.Expiry expiry, boolean wholeWord) {
        if (phrase == null || phrase.isBlank()) throw new InvalidPostFilterException("Phrase can't be blank");
        var filter = create(accountId, new PostFilterChange(titleFrom(phrase), contexts, action, expiry,
                List.of(new PostFilterChange.KeywordEdit(null, phrase, wholeWord, false))));
        return new LegacyFilter(filter, filter.keywords().getFirst());
    }

    /**
     * Changes the keyword, and what its filter applies to. The phrase also names a filter that has only this keyword. A
     * filter with others can only have its keyword changed, as on Mastodon, since the change would apply to all of them.
     *
     * @param phrase       null to keep it
     * @param irreversible null to keep the action. False keeps {@link PostFilter.Action#BLUR}, which isn't irreversible
     *                     either
     * @param wholeWord    null to keep it
     * @return the keyword and its filter as changed, or empty if the account has no such keyword
     * @throws InvalidPostFilterException if the change is refused
     */
    @Transactional
    public Optional<LegacyFilter> legacyUpdate(UUID accountId, UUID keywordId, String phrase,
                                               Set<PostFilter.Context> contexts, Boolean irreversible,
                                               PostFilterChange.Expiry expiry, Boolean wholeWord) {
        repository.lockAccount(accountId);
        var found = legacyFind(accountId, keywordId);
        if (found.isEmpty()) return Optional.empty();
        var filter = found.get().filter();
        PostFilter.Action action = null;
        if (irreversible != null) {
            action = irreversible ? PostFilter.Action.HIDE
                    : filter.action() == PostFilter.Action.BLUR ? null : PostFilter.Action.WARN;
        }
        var single = filter.keywords().size() == 1;
        var changesFilter = (contexts != null && !contexts.equals(filter.contexts()))
                || (action != null && action != filter.action())
                || (expiry != null && !Objects.equals(expiry.at(), filter.expiresAt()));
        if (changesFilter && !single) {
            throw new InvalidPostFilterException(
                    "This filter has more than one keyword, so only its phrase and whole-word setting can be changed here");
        }
        if (phrase != null && phrase.isBlank()) throw new InvalidPostFilterException("Phrase can't be blank");
        update(accountId, filter.id(), new PostFilterChange(single && phrase != null ? titleFrom(phrase) : null, contexts,
                action, expiry,
                List.of(new PostFilterChange.KeywordEdit(keywordId, phrase, wholeWord, false))));
        return legacyFind(accountId, keywordId);
    }

    /**
     * Removes the keyword, and its filter too if that leaves it empty, unlike Mastodon, so that a client that only uses
     * the v1 API doesn't leave behind filters it can't see.
     *
     * @return whether the account had the keyword
     */
    @Transactional
    public boolean legacyDelete(UUID accountId, UUID keywordId) {
        repository.lockAccount(accountId);
        var found = legacyFind(accountId, keywordId);
        if (found.isEmpty()) return false;
        var filter = found.get().filter();
        if (filter.keywords().size() == 1 && filter.statuses().isEmpty()) {
            repository.delete(filter.id());
        } else {
            repository.deleteKeyword(keywordId);
        }
        changed(accountId);
        return true;
    }

    /**
     * Doesn't tell the account's clients, since the account is gone.
     */
    public void removeForActor(UUID accountId) {
        repository.deleteForAccount(accountId);
    }

    /**
     * Mastodon ignores a new keyword that's left blank, since forms send empty rows.
     */
    private void applyKeywords(UUID filterId, List<PostFilter.Keyword> old, List<PostFilterChange.KeywordEdit> edits) {
        var byId = old.stream().collect(Collectors.toMap(PostFilter.Keyword::id, keyword -> keyword));
        var count = old.size();
        var removed = new HashSet<UUID>();
        for (var edit : edits) {
            if (edit.id() == null) {
                if (edit.remove() || edit.keyword() == null || edit.keyword().isBlank()) continue;
                if (++count > MAX_KEYWORDS_PER_FILTER) throw tooManyKeywords();
                repository.insertKeyword(filterId, keyword(edit.keyword()), edit.wholeWord() == null || edit.wholeWord());
            } else if (edit.remove()) {
                if (removed.add(edit.id())) {
                    repository.deleteKeyword(edit.id());
                    count--;
                }
            } else if (!removed.contains(edit.id())) {
                var current = byId.get(edit.id());
                repository.updateKeyword(edit.id(), edit.keyword() == null ? current.keyword() : keyword(edit.keyword()),
                        edit.wholeWord() == null ? current.wholeWord() : edit.wholeWord());
            }
        }
    }

    private static Optional<PostFilter.Keyword> keywordIn(PostFilter filter, UUID keywordId) {
        return filter.keywords().stream().filter(keyword -> keyword.id().equals(keywordId)).findFirst();
    }

    /**
     * A phrase can be longer than a title, so a long one is cut short to name its filter.
     */
    private static String titleFrom(String phrase) {
        var stripped = phrase.strip();
        return stripped.codePointCount(0, stripped.length()) <= MAX_TITLE_LENGTH ? stripped
                : stripped.substring(0, stripped.offsetByCodePoints(0, MAX_TITLE_LENGTH));
    }

    private static String title(String title) {
        var stripped = title.strip();
        if (stripped.isEmpty()) throw new InvalidPostFilterException("Title can't be blank");
        if (stripped.codePointCount(0, stripped.length()) > MAX_TITLE_LENGTH) {
            throw new InvalidPostFilterException("Title is too long (maximum is " + MAX_TITLE_LENGTH + " characters)");
        }
        return stripped;
    }

    private static String keyword(String keyword) {
        var stripped = keyword == null ? "" : keyword.strip();
        if (stripped.isEmpty()) throw new InvalidPostFilterException("Keyword can't be blank");
        if (stripped.codePointCount(0, stripped.length()) > MAX_KEYWORD_LENGTH) {
            throw new InvalidPostFilterException("Keyword is too long (maximum is " + MAX_KEYWORD_LENGTH + " characters)");
        }
        return stripped;
    }

    private static InvalidPostFilterException tooManyKeywords() {
        return new InvalidPostFilterException("A filter can't have more than " + MAX_KEYWORDS_PER_FILTER + " keywords");
    }

    private void changed(UUID accountId) {
        events.publishEvent(new FiltersChanged(accountId));
    }
}
