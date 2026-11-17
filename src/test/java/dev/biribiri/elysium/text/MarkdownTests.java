package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownTests {

    private static final URI TAGS = URI.create("https://e.example");

    private static String render(String text) {
        return Markdown.render(text, mention -> null, null);
    }

    private static String link(String href, String text) {
        return "<a href=\"" + href + "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" + text + "</a>";
    }

    @Test
    void rendersInlineFormatting() {
        assertThat(render("*a* **b** ~~c~~ `d <e>`"))
                .isEqualTo("<p><em>a</em> <strong>b</strong> <del>c</del> <code>d &lt;e&gt;</code></p>");
    }

    @Test
    void breaksLinesWhereTheAuthorDid() {
        assertThat(render("one\ntwo\n\nthree")).isEqualTo("<p>one<br>two</p><p>three</p>");
    }

    @Test
    void keepsTheLinesOfCodeBlocks() {
        assertThat(render("```java\nint a;\nint b;\n```")).isEqualTo("<pre><code>int a;\nint b;\n</code></pre>");
    }

    @Test
    void rendersListsAndQuotes() {
        assertThat(render("- a\n- *b* c\n\n1. d\n\n> e"))
                .isEqualTo("<ul><li>a</li><li><em>b</em> c</li></ul><ol><li>d</li></ol><blockquote><p>e</p></blockquote>");
    }

    @Test
    void keepsWhereANumberedListStarts() {
        assertThat(render("2024. What a year")).isEqualTo("<ol start=\"2024\"><li>What a year</li></ol>");
    }

    @Test
    void showsHeadingsAsBoldParagraphs() {
        assertThat(render("# Title\nbody")).isEqualTo("<p><strong>Title</strong></p><p>body</p>");
    }

    @Test
    void escapesHtml() {
        assertThat(render("<script>alert(1)</script> <b>hi</b>"))
                .isEqualTo("<p>&lt;script&gt;alert(1)&lt;/script&gt; &lt;b&gt;hi&lt;/b&gt;</p>");
        assertThat(render("<div onclick=\"x\">\nhi\n</div>"))
                .isEqualTo("<p>&lt;div onclick=\"x\"&gt;<br>hi<br>&lt;/div&gt;</p>");
    }

    @Test
    void keepsOnlyWebLinks() {
        assertThat(render("[a](https://a.example/x?y=1&z=2) [b](javascript:alert(1))"))
                .isEqualTo("<p>" + link("https://a.example/x?y=1&amp;z=2", "a") + " <a rel=\"nofollow noopener noreferrer\" "
                        + "target=\"_blank\">b</a></p>");
    }

    @Test
    void dropsRulesAndLinkTitles() {
        assertThat(render("a\n\n---\n\n[b](https://a.example \"t\")"))
                .isEqualTo("<p>a</p><p>" + link("https://a.example", "b") + "</p>");
    }

    @Test
    void linksToImagesRatherThanShowingThem() {
        assertThat(render("![a cat](https://a.example/cat.png)"))
                .isEqualTo("<p>" + link("https://a.example/cat.png", "a cat") + "</p>");
    }

    @Test
    void showsAnImageInALinkAsItsText() {
        assertThat(render("[![build](https://a.example/badge.svg)](https://b.example)"))
                .isEqualTo("<p>" + link("https://b.example", "build") + "</p>");
    }

    @Test
    void linksUrlsMentionsAndHashtagsAsPlainTextDoes() {
        var html = Markdown.render("**@bob_x** see https://a.example/a_b_c #Cats", mention ->
                new PlainText.MentionLink(URI.create("https://b.example/users/bob_x"), "bob_x@b.example"), TAGS);
        assertThat(html).isEqualTo(
                "<p><strong><span class=\"h-card\" translate=\"no\"><a href=\"https://b.example/users/bob_x\" "
                        + "class=\"u-url mention\" title=\"@bob_x@b.example\">@<span>bob_x</span></a></span></strong> see "
                        + link("https://a.example/a_b_c", "https://a.example/a_b_c") + " <a href=\"https://e.example/tags/cats\" "
                        + "class=\"mention hashtag\" rel=\"tag\">#<span>Cats</span></a></p>");
    }

    @Test
    void keepsUrlsWholeWhereverTheyAre() {
        var url = "https://a.example/_x_/*y*/z";
        assertThat(render("see " + url + " and _this_"))
                .isEqualTo("<p>see " + link(url, url) + " and <em>this</em></p>");
        assertThat(render("`" + url + "`")).isEqualTo("<p><code>" + url + "</code></p>");
        assertThat(render("```\n" + url + "\n```")).isEqualTo("<pre><code>" + url + "\n</code></pre>");
        assertThat(render("[a](" + url + ")")).isEqualTo("<p>" + link(url, "a") + "</p>");
        assertThat(render("[" + url + "](https://b.example)"))
                .isEqualTo("<p>" + link("https://b.example", url) + "</p>");
    }

    @Test
    void leavesCodeAndLinkTextUnlinked() {
        var html = Markdown.render("`@bob #tag` [@bob](https://a.example)", mention ->
                new PlainText.MentionLink(URI.create("https://b.example/users/bob"), "bob@b.example"), TAGS);
        assertThat(html).isEqualTo("<p><code>@bob #tag</code> " + link("https://a.example", "@bob") + "</p>");
    }

    @Test
    void findsMentionsAndHashtagsOnlyWhereTheyreLinked() {
        var text = Markdown.linkableText("hi @alice\n\n```\n@bob\n```\n`#code` [@carol](https://a.example) #real");
        assertThat(Mentions.find(text)).extracting(Mentions.Match::username).containsExactly("alice");
        assertThat(Hashtags.names(text)).containsExactly("real");
    }
}
