package dev.biribiri.elysium.content;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostFilterMatcherTests {

    @Test
    void wholeWordsOnlyMatchWholeWords() {
        var matcher = matcher(keyword("cat", true));
        assertThat(matches(matcher, "a cat sat")).containsExactly("cat");
        assertThat(matches(matcher, "a Cat.")).containsExactly("Cat");
        assertThat(matches(matcher, "concatenate")).isEmpty();
        assertThat(matches(matcher, "cats")).isEmpty();
    }

    @Test
    void partsOfWordsMatchOtherwise() {
        assertThat(matches(matcher(keyword("cat", false)), "concatenate")).containsExactly("cat");
    }

    /**
     * A boundary next to a character that isn't part of a word would only match where the keyword is inside one.
     */
    @Test
    void boundariesAreOnlyAtWordCharacters() {
        assertThat(matches(matcher(keyword("#tag", true)), "about #tag today")).containsExactly("#tag");
        assertThat(matches(matcher(keyword("c++", true)), "I write c++ code")).containsExactly("c++");
        assertThat(matches(matcher(keyword("#tag", true)), "#tagged")).isEmpty();
    }

    @Test
    void casesAndScriptsAreMatchedAsUnicodeHasThem() {
        assertThat(matches(matcher(keyword("straße", true)), "Die STRASSE und die Straße")).containsExactly("Straße");
        assertThat(matches(matcher(keyword("ÉTÉ", true)), "un été chaud")).containsExactly("été");
        assertThat(matches(matcher(keyword("été", true)), "étérnel")).isEmpty();
    }

    @Test
    void keywordsAreNotPatterns() {
        assertThat(matches(matcher(keyword(".*", false)), "nothing here")).isEmpty();
    }

    @Test
    void statusFiltersMatchBoostsOfTheirStatuses() {
        var filtered = UUID.randomUUID();
        var filter = new PostFilter(UUID.randomUUID(), "f", Set.of(PostFilter.Context.HOME), PostFilter.Action.HIDE,
                null, List.of(), List.of(new PostFilter.FilteredStatus(UUID.randomUUID(), filtered)));
        var matcher = new PostFilterMatcher(List.of(filter));
        var boost = status(UUID.randomUUID(), filtered, "");

        var found = matcher.match(boost, "");
        assertThat(found).singleElement().satisfies(match -> {
            assertThat(match.statusMatches()).containsExactly(filtered);
            assertThat(match.hidesIn(PostFilter.Context.HOME)).isTrue();
            assertThat(match.hidesIn(PostFilter.Context.PUBLIC)).isFalse();
        });
    }

    @Test
    void searchableTextIncludesWarningsPollsAndDescriptions() {
        var status = status(UUID.randomUUID(), null, "body");
        assertThat(PostFilterMatcher.searchableText(status, null, List.of())).isEqualTo("warning\n\nbody");
    }

    private static PostFilter.Keyword keyword(String text, boolean wholeWord) {
        return new PostFilter.Keyword(UUID.randomUUID(), text, wholeWord);
    }

    private static PostFilterMatcher matcher(PostFilter.Keyword keyword) {
        return new PostFilterMatcher(List.of(new PostFilter(UUID.randomUUID(), "f", Set.of(PostFilter.Context.HOME),
                PostFilter.Action.WARN, null, List.of(keyword), List.of())));
    }

    private static List<String> matches(PostFilterMatcher matcher, String text) {
        return matcher.match(status(UUID.randomUUID(), null, text), text).stream()
                .flatMap(match -> match.keywordMatches().stream()).toList();
    }

    private static Status status(UUID id, UUID reblogOfId, String text) {
        return new Status(id, URI.create("https://example.test/s/" + id), UUID.randomUUID(), text, "<p>" + text + "</p>",
                "warning", null, Visibility.PUBLIC, false, false, null, null, null, null, Instant.now(), null,
                reblogOfId, 0, null, null, false, ContentType.PLAIN);
    }
}
