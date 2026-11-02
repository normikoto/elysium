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
        assertThat(properties.domain()).isEqualTo("elysium.example");
        assertThat(properties.resolve("/users/admin")).isEqualTo(URI.create("https://elysium.example/users/admin"));
    }

    @Test
    void keepsNonDefaultPort() {
        var properties = new InstanceProperties(URI.create("http://localhost:8080"));

        assertThat(properties.domain()).isEqualTo("localhost:8080");
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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InstanceProperties.class)
    static class PropertiesConfiguration {
    }
}
