package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MentionsTests {

    @Test
    void findsMentionsWithAndWithoutADomain() {
        assertThat(Mentions.find("hi @alice and @bob@example.com!")).containsExactly(
                new Mentions.Match(3, 9, "alice", null),
                new Mentions.Match(14, 30, "bob", "example.com"));
    }

    @Test
    void leavesOutTrailingPunctuation() {
        assertThat(Mentions.find("@bob@example.com.")).containsExactly(new Mentions.Match(0, 16, "bob", "example.com"));
        assertThat(Mentions.find("@bob.")).containsExactly(new Mentions.Match(0, 4, "bob", null));
    }

    @Test
    void ignoresEmailAddresses() {
        assertThat(Mentions.find("mail bob@example.com")).isEmpty();
    }

    @Test
    void ignoresMentionsInsideUrls() {
        assertThat(Mentions.find("https://example.com/@bob and https://example.com/?u=@bob")).isEmpty();
    }

    @Test
    void ignoresALoneAt() {
        assertThat(Mentions.find("@ alone @")).isEmpty();
    }

    @Test
    void doesNotCountTheDomainTowardTheLength() {
        assertThat(Urls.countCharacters("@bob@example.com hi")).isEqualTo(7);
    }

    @Test
    void limitsWhatDomainsCanSave() {
        var mention = "@b@" + "d".repeat(99) + ".example";
        var text = (mention + " ").repeat(100).strip();
        assertThat(Urls.countCharacters(text))
                .isEqualTo(text.length() - Mentions.MAX_DOMAIN_SAVINGS);
    }
}
