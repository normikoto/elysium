package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedLinksTests {

    @Test
    void writesTheSamePageTheSameWay() {
        assertThat(SharedLinks.in("""
                <p><a href="HTTPS://News.Example:443/story?q=one">One</a>
                <a href="https://news.example/story?q=one#part">Again</a>
                <a href="http://news.example:8080/other">Other</a></p>"""))
                .containsExactly("https://news.example/story?q=one", "http://news.example:8080/other");
    }

    @Test
    void leavesOutMentionsHashtagsAndQuotes() {
        assertThat(SharedLinks.in("""
                <p><span class="h-card"><a href="https://people.example/@name" class="u-url mention">@name</a></span>
                <a href="https://local.example/tags/cats" class="mention hashtag" rel="tag">#cats</a>
                <a href="https://people.example/tags/dogs" class="hashtag">#dogs</a></p>
                <p class="quote-inline">RE: <a href="https://posts.example/quoted">quoted</a></p>""")).isEmpty();
    }

    @Test
    void leavesOutWhatIsNotAWebPage() {
        assertThat(SharedLinks.in("""
                <a href="mailto:someone@mail.example">Mail</a><a href="/relative">Here</a>
                <a href="https://user:secret@news.example/">Login</a>
                <a href="https://news.example/%s">Long</a>""".formatted("x".repeat(2048)))).isEmpty();
    }

    @Test
    void findsNothingInTextWithoutLinks() {
        assertThat(SharedLinks.in("<p>No links</p>")).isEmpty();
    }
}
