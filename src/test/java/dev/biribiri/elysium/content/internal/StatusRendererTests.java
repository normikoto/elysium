package dev.biribiri.elysium.content.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusRendererTests {

    private static String link(String href, String text) {
        return "<a href=\"" + href + "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" + text + "</a>";
    }

    private static String link(String url) {
        return link(url, url);
    }

    @Test
    void wrapsParagraphsAndLineBreaks() {
        assertThat(StatusRenderer.render("one\ntwo\n\nthree")).isEqualTo("<p>one<br>two</p><p>three</p>");
    }

    @Test
    void escapesMarkup() {
        assertThat(StatusRenderer.render("<script>alert('x') & \"y\"</script>"))
                .isEqualTo("<p>&lt;script&gt;alert(&#39;x&#39;) &amp; &quot;y&quot;&lt;/script&gt;</p>");
    }

    @Test
    void leavesNonAsciiTextAsIs() {
        assertThat(StatusRenderer.render("café 日本語 😀")).isEqualTo("<p>café 日本語 😀</p>");
    }

    @Test
    void linksHttpAndHttpsUrls() {
        assertThat(StatusRenderer.render("see http://a.example and HTTPS://b.example/c"))
                .isEqualTo("<p>see " + link("http://a.example") + " and " + link("HTTPS://b.example/c") + "</p>");
    }

    @Test
    void escapesUrls() {
        assertThat(StatusRenderer.render("https://a.example/?x=1&y=2"))
                .isEqualTo("<p>" + link("https://a.example/?x=1&amp;y=2") + "</p>");
    }

    @Test
    void urlsCannotBreakOutOfTheAttribute() {
        assertThat(StatusRenderer.render("https://a.example/\"onclick=alert(1)"))
                .isEqualTo("<p>" + link("https://a.example/") + "&quot;onclick=alert(1)</p>");
        assertThat(StatusRenderer.render("https://a.example/'x"))
                .isEqualTo("<p>" + link("https://a.example/&#39;x") + "</p>");
    }

    @Test
    void leavesTrailingPunctuationOutOfLinks() {
        assertThat(StatusRenderer.render("Look: https://a.example/b."))
                .isEqualTo("<p>Look: " + link("https://a.example/b") + ".</p>");
        assertThat(StatusRenderer.render("(https://a.example/b)!"))
                .isEqualTo("<p>(" + link("https://a.example/b") + ")!</p>");
    }

    @Test
    void keepsBalancedBracketsInLinks() {
        assertThat(StatusRenderer.render("https://a.example/Foo_(bar)"))
                .isEqualTo("<p>" + link("https://a.example/Foo_(bar)") + "</p>");
    }

    @Test
    void onlyLinksHttpUrls() {
        assertThat(StatusRenderer.render("javascript:alert(1) ftp://a.example xhttp://a.example https://"))
                .isEqualTo("<p>javascript:alert(1) ftp://a.example xhttp://a.example https://</p>");
    }
}
