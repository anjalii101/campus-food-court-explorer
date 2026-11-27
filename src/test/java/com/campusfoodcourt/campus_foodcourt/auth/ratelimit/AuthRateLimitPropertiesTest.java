package com.campusfoodcourt.campus_foodcourt.auth.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.support.ResourcePropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimitPropertiesTest {

    /**
     * Binds application.properties alone (no environment variables, no test profile), so this pins the
     * documented production defaults.
     */
    @Test
    void productionDefaultsAreTheDocumentedOnes() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new ResourcePropertySource("classpath:application.properties"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));

        AuthRateLimitProperties p = binder.bind("app.auth.rate-limit", AuthRateLimitProperties.class).get();

        assertThat(p.login()).isEqualTo(new AuthRateLimitProperties.Rule(20, Duration.ofMinutes(1)));
        assertThat(p.register()).isEqualTo(new AuthRateLimitProperties.Rule(20, Duration.ofMinutes(10)));
        assertThat(p.refresh()).isEqualTo(new AuthRateLimitProperties.Rule(60, Duration.ofMinutes(1)));
        assertThat(p.maxTrackedClients()).isEqualTo(20_000);
        assertThat(p.ipv6PrefixLength()).isEqualTo(64);
        assertThat(binder.bind("server.forward-headers-strategy", String.class).get()).isEqualTo("none");
    }

    @Test
    void windowMustBePositive() {
        assertThatThrownBy(() -> new AuthRateLimitProperties.Rule(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("window");
        assertThatThrownBy(() -> new AuthRateLimitProperties.Rule(5, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new AuthRateLimitProperties.Rule(5, Duration.ofSeconds(1))).doesNotThrowAnyException();
    }
}
