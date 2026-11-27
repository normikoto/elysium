package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.trends.enabled=false")
@AutoConfigureMockMvc
class TrendsDisabledIntegrationTests {

    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private MockMvc mvc;

    @Test
    void returnsEmptyListsWhenTrendsAreDisabled() throws Exception {
        for (var name : List.of("offalice", "offbob")) {
            var actor = actors.createLocalIfAbsent(name, ignored -> {}).orElseThrow();
            statuses.create(actor.id(), new NewStatus("#News https://news.example/story", null, null,
                    Visibility.PUBLIC, false, null));
        }

        for (var endpoint : List.of("tags", "statuses", "links")) {
            mvc.perform(get("/api/v1/trends/" + endpoint)).andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
            mvc.perform(get("/api/v1/trends/" + endpoint).param("offset", "1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty()).andExpect(header().doesNotExist(HttpHeaders.LINK));
        }
    }
}
