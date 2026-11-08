package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class PlainTextTests {

    private static String link(String href, String text) {
        return "<a href=\"" + href + "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" + text + "</a>";
    }

    private static String link(String url) {
        return link(url, url);
    }

    @Test
    void linksMentionsThatHaveATarget() {
        var html = PlainText.render("hi @bob@Example.com, @nobody & https://a.example/@bob", mention ->
                mention.username().equals("bob")
                        ? new PlainText.MentionLink(URI.create("https://example.com/users/bob?a=1&b=2"), "bob@example.com")
                        : null);
        assertThat(html).isEqualTo("<p>hi <span class=\"h-card\" translate=\"no\"><a href=\"https://example.com/users/bob?a=1&amp;b=2\" "
                + "class=\"u-url mention\" title=\"@bob@example.com\">@<span>bob</span></a></span>, @nobody &amp; "
                + link("https://a.example/@bob") + "</p>");
    }

    @Test
    void wrapsParagraphsAndLineBreaks() {
        assertThat(PlainText.render("one\ntwo\n\nthree")).isEqualTo("<p>one<br>two</p><p>three</p>");
    }

    @Test
    void escapesMarkup() {
        assertThat(PlainText.render("<script>alert('x') & \"y\"</script>"))
                .isEqualTo("<p>&lt;script&gt;alert(&#39;x&#39;) &amp; &quot;y&quot;&lt;/script&gt;</p>");
    }

    @Test
    void leavesNonAsciiTextAsIs() {
        assertThat(PlainText.render("café 日本語 😀")).isEqualTo("<p>café 日本語 😀</p>");
    }

    @Test
    void linksHttpAndHttpsUrls() {
        assertThat(PlainText.render("see http://a.example and HTTPS://b.example/c"))
                .isEqualTo("<p>see " + link("http://a.example") + " and " + link("HTTPS://b.example/c") + "</p>");
    }

    @Test
    void escapesUrls() {
        assertThat(PlainText.render("https://a.example/?x=1&y=2"))
                .isEqualTo("<p>" + link("https://a.example/?x=1&amp;y=2") + "</p>");
    }

    @Test
    void urlsCannotBreakOutOfTheAttribute() {
        assertThat(PlainText.render("https://a.example/\"onclick=alert(1)"))
                .isEqualTo("<p>" + link("https://a.example/") + "&quot;onclick=alert(1)</p>");
        assertThat(PlainText.render("https://a.example/'x"))
                .isEqualTo("<p>" + link("https://a.example/&#39;x") + "</p>");
    }

    @Test
    void leavesTrailingPunctuationOutOfLinks() {
        assertThat(PlainText.render("Look: https://a.example/b."))
                .isEqualTo("<p>Look: " + link("https://a.example/b") + ".</p>");
        assertThat(PlainText.render("(https://a.example/b)!"))
                .isEqualTo("<p>(" + link("https://a.example/b") + ")!</p>");
    }

    @Test
    void keepsBalancedBracketsInLinks() {
        assertThat(PlainText.render("https://a.example/Foo_(bar)"))
                .isEqualTo("<p>" + link("https://a.example/Foo_(bar)") + "</p>");
    }

    @Test
    void keepsTextAfterAUrlOutOfTheLink() {
        assertThat(PlainText.render("https://a.example/bを見て。"))
                .isEqualTo("<p>" + link("https://a.example/b") + "を見て。</p>");
        assertThat(PlainText.render("https://a.example　次"))
                .isEqualTo("<p>" + link("https://a.example") + "　次</p>");
    }

    @Test
    void linksUrlsWithNonAsciiLetters() {
        assertThat(PlainText.render("https://ru.wikipedia.org/wiki/Москва"))
                .isEqualTo("<p>" + link("https://ru.wikipedia.org/wiki/Москва") + "</p>");
    }

    @Test
    void onlyLinksHttpUrls() {
        assertThat(PlainText.render("javascript:alert(1) ftp://a.example xhttp://a.example https://"))
                .isEqualTo("<p>javascript:alert(1) ftp://a.example xhttp://a.example https://</p>");
    }
}
