package dev.biribiri.elysium.moderation;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.internal.DomainBlockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DomainBlocksTests {

    @ParameterizedTest
    @CsvSource({
            "https://self.example, self.example",
            "https://SELF.example, self.example",
            "https://self.example., self.example",
            "https://self.example., SELF.example.",
            "https://self.example.:8443, self.example",
            "https://sub.self.example., self.example"
    })
    void refusesToBlockThisServer(String baseUrl, String domain) {
        assertThatThrownBy(() -> blocksOn(baseUrl).create(domain, null))
                .isInstanceOf(InvalidDomainBlockException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void allowsASubdomainOfThisServerWhenItsUrlEndsInADot() {
        var block = blocksOn("https://self.example.").create("sub.self.example", null);

        assertThat(block.domain()).isEqualTo("sub.self.example");
    }

    @Test
    void allowsBlocksOnAServerAtAnIpAddress() {
        var block = blocksOn("http://192.0.2.1").create("spam.example", null);

        assertThat(block.domain()).isEqualTo("spam.example");
    }

    private static DomainBlocks blocksOn(String baseUrl) {
        var repository = mock(DomainBlockRepository.class);
        when(repository.insert(any())).thenReturn(true);
        return new DomainBlocks(repository, new InstanceProperties(URI.create(baseUrl)),
                mock(ApplicationEventPublisher.class));
    }
}
