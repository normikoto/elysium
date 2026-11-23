package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an admin who has turned off {@code elysium.usage.publish} gets.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://unpublished.example",
        "elysium.jobs.worker-enabled=false",
        "elysium.usage.publish=false"
})
@AutoConfigureMockMvc
class UsageNotPublishedIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActorService actors;

    @Test
    void theWeeklyCountsAreNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/instance/activity")).andExpect(status().isNotFound());
    }

    @Test
    void nodeInfoLeavesOutTheActiveCounts() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        for (var version : new String[]{"2.0", "2.1"}) {
            mockMvc.perform(get("/nodeinfo/" + version).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usage.users.total").value(actors.countLocal()))
                    .andExpect(jsonPath("$.usage.users.activeMonth").doesNotExist())
                    .andExpect(jsonPath("$.usage.users.activeHalfyear").doesNotExist());
        }
    }

    @Test
    void theInstanceCountsEveryAccountAsActive() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        mockMvc.perform(get("/api/v2/instance"))
                .andExpect(jsonPath("$.usage.users.active_month").value(actors.countLocal()));
    }
}
