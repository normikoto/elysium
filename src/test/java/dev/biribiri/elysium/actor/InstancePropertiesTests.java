package dev.biribiri.elysium.actor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InstancePropertiesTests {

    @Test
    void failsStartupWhenBaseUrlIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("elysium.instance.base-url is required"));
    }

    @Test
    void normalizesCaseAndDefaultPort() {
        var properties = new InstanceProperties(URI.create("HTTPS://Elysium.Example:443/"));

        assertThat(properties.baseUrl()).isEqualTo(URI.create("https://elysium.example"));
        assertThat(properties.authority()).isEqualTo("elysium.example");
        assertThat(properties.origin()).isEqualTo("https://elysium.example");
        assertThat(properties.resolve("/users/admin")).isEqualTo(URI.create("https://elysium.example/users/admin"));
    }

    @Test
    void keepsNonDefaultPort() {
        var properties = new InstanceProperties(URI.create("http://localhost:8080"));

        assertThat(properties.authority()).isEqualTo("localhost:8080");
        assertThat(properties.origin()).isEqualTo("http://localhost:8080");
    }

    @Test
    void rejectsCredentialsWithoutEchoingThem() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InstanceProperties(URI.create("https://user:secret@elysium.example")))
                .withMessageNotContaining("secret");
    }

    @Test
    void rejectsOtherInvalidUrls() {
        for (var url : new String[]{"ftp://elysium.example", "elysium.example", "https://elysium.example/path",
                "https://elysium.example?a=b", "https://elysium.example#a"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new InstanceProperties(URI.create(url)));
        }
    }

    @Test
    void accountDomainDefaultsToTheAuthority() {
        var properties = new InstanceProperties(URI.create("http://localhost:8080"), " ");

        assertThat(properties.accountDomain()).isEqualTo("localhost:8080");
        assertThat(properties.isLocalDomain("LOCALHOST:8080")).isTrue();
        assertThat(properties.isLocalDomain("localhost")).isFalse();
    }

    @Test
    void separateAccountDomainIsNormalizedAndBothAreLocal() {
        var properties = new InstanceProperties(URI.create("https://social.example"), " Example.COM ");

        assertThat(properties.accountDomain()).isEqualTo("example.com");
        assertThat(properties.authority()).isEqualTo("social.example");
        assertThat(properties.isLocalDomain("example.com")).isTrue();
        assertThat(properties.isLocalDomain("Social.Example")).isTrue();
        assertThat(properties.isLocalDomain("other.example")).isFalse();
        assertThat(properties.isLocalDomain(null)).isFalse();
        assertThat(new InstanceProperties(URI.create("https://social.example"), "example.com:8443").accountDomain())
                .isEqualTo("example.com:8443");
        assertThat(new InstanceProperties(URI.create("https://social.example"), "example.com:443").accountDomain())
                .isEqualTo("example.com");
    }

    @Test
    void rejectsAccountDomainsThatArentDomains() {
        for (var domain : new String[]{"https://example.com", "example.com/path", "alice@example.com", ":8080",
                "example.com?a=b", "example .com", "example.com:", "example.com."}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new InstanceProperties(URI.create("https://social.example"), domain));
        }
    }

    @Test
    void bindsAccountDomain() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("elysium.instance.base-url=https://social.example",
                        "elysium.instance.account-domain=example.com")
                .run(context -> assertThat(context.getBean(InstanceProperties.class).accountDomain())
                        .isEqualTo("example.com"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InstanceProperties.class)
    static class PropertiesConfiguration {
    }
}
