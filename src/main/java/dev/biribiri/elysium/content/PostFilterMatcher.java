package dev.biribiri.elysium.content;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One account's active filters, compiled once to be matched against many statuses.
 * <p>
 * Keywords match as Mastodon's do: without regard to case, and for a whole-word keyword, with a word boundary only at an
 * end that's a word character, so that a keyword such as {@code #tag} or {@code c++} still matches.
 */
public final class PostFilterMatcher {

    public static final PostFilterMatcher NONE = new PostFilterMatcher(List.of());

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern WORD_CHARACTER = Pattern.compile("\\w", FLAGS);

    private final List<Compiled> filters;

    PostFilterMatcher(List<PostFilter> filters) {
        this.filters = filters.stream().map(Compiled::of).toList();
    }

    public boolean isEmpty() {
        return filters.isEmpty();
    }

    /**
     * The text that keywords are matched against, which is what Mastodon searches: the content warning, the text, the
     * poll's options, and the attachments' descriptions.
     *
     * @param poll the status's poll, or null
     */
    public static String searchableText(Status status, Poll poll, Collection<MediaAttachment> media) {
        return Stream.of(
                        Stream.of(status.spoilerText(), status.text()),
                        poll == null ? Stream.<String>empty() : poll.options().stream().map(Poll.Option::title),
                        media.stream().map(MediaAttachment::description))
                .flatMap(s -> s)
                .filter(text -> text != null && !text.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * @param status the status as it's listed, whose boosted status also counts for a filter's statuses
     * @param text   the {@link #searchableText} of the status, or of the one it boosts
     */
    public List<PostFilterMatch> match(Status status, String text) {
        return filters.stream().flatMap(filter -> filter.match(status, text).stream()).toList();
    }

    private record Compiled(PostFilter filter, Pattern keywords, Set<UUID> statusIds) {

        static Compiled of(PostFilter filter) {
            var pattern = filter.keywords().isEmpty() ? null : Pattern.compile(filter.keywords().stream()
                    .map(Compiled::regex).collect(Collectors.joining("|")), FLAGS);
            return new Compiled(filter, pattern, filter.statuses().stream()
                    .map(PostFilter.FilteredStatus::statusId).collect(Collectors.toSet()));
        }

        private static String regex(PostFilter.Keyword keyword) {
            var text = keyword.keyword();
            var quoted = Pattern.quote(text);
            if (!keyword.wholeWord() || text.isEmpty()) {
                return quoted;
            }
            var first = new String(Character.toChars(text.codePointAt(0)));
            var last = new String(Character.toChars(text.codePointBefore(text.length())));
            return (WORD_CHARACTER.matcher(first).matches() ? "\\b" : "") + quoted
                    + (WORD_CHARACTER.matcher(last).matches() ? "\\b" : "");
        }

        Optional<PostFilterMatch> match(Status status, String text) {
            var keywordMatches = new LinkedHashSet<String>();
            if (keywords != null && text != null) {
                var matcher = keywords.matcher(text);
                while (matcher.find()) {
                    keywordMatches.add(matcher.group());
                }
            }
            var statusMatches = Stream.of(status.id(), status.reblogOfId())
                    .filter(id -> id != null && statusIds.contains(id)).distinct().toList();
            if (keywordMatches.isEmpty() && statusMatches.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PostFilterMatch(filter, List.copyOf(keywordMatches), statusMatches));
        }
    }
}
