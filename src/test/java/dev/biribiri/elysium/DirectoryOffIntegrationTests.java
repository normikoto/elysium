package dev.biribiri.elysium;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://directory-off.example",
        "elysium.profiles.directory=false"
})
@AutoConfigureMockMvc
class DirectoryOffIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void theDirectoryIsGoneWhenItsTurnedOff() throws Exception {
        mvc.perform(get("/api/v1/directory")).andExpect(status().isNotFound());
        mvc.perform(get("/directory")).andExpect(status().isNotFound());
        mvc.perform(get("/")).andExpect(content().string(not(containsString("href=\"/directory\""))));
    }
}
