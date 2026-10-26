package com.finallyapp.market.massive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/** Excluded by default (surefire excludedGroups=live). Run with -DexcludedGroups= and MASSIVE_API_KEY set. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "MASSIVE_API_KEY", matches = ".+")
class MassiveLiveSmokeTest {

    @Test
    void fetchesSomething() {
        var client = new MassiveClient(RestClient.builder(), "https://api.massive.com", System.getenv("MASSIVE_API_KEY"));
        assertThat(client.groupedDaily(LocalDate.now(ZoneId.of("America/New_York")).minusDays(3))).isNotNull();
    }
}
