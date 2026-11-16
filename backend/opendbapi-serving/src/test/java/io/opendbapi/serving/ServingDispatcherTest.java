package io.opendbapi.serving;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendbapi.core.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ServingDispatcher.class)
@Import(CoreConfiguration.class)
@TestPropertySource(properties = "opendbapi.serving.context=data")
class ServingDispatcherTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void unknownEndpointIsProblemJson404() throws Exception {
        // Act + Assert
        mvc.perform(get("/data/sales/v1/users/42"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.detail").value(
                        "No published endpoint matches GET /v1/users/42 in workspace 'sales'."));
    }

    @Test
    void servesEveryMethodUnderTheContext() throws Exception {
        // Act + Assert
        mvc.perform(post("/data/sales/v1/orders"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void pathsOutsideTheContextAreNotServing() throws Exception {
        // Act + Assert: the default context "api" is not mapped when context=data
        mvc.perform(get("/api/sales/v1/users"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
