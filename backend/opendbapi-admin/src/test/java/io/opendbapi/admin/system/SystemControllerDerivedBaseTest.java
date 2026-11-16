package io.opendbapi.admin.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import io.opendbapi.core.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CoreConfiguration.class)
class SystemControllerDerivedBaseTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void derivesServingBaseFromRequestWhenNotConfigured() throws Exception {
        // Act + Assert
        mvc.perform(get("http://opendbapi.local:8520/admin/api/system/info"))
                .andExpect(jsonPath("$.servingBaseUrl").value("http://opendbapi.local:8520/api"));
    }
}
