package io.opendbapi.admin.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendbapi.core.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CoreConfiguration.class)
@TestPropertySource(properties = {
        "opendbapi.serving.context=data",
        "opendbapi.serving.url-base=https://api.example.com"
})
class SystemControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void reportsVersionModeAndServingBase() throws Exception {
        // Act + Assert
        mvc.perform(get("/admin/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("dev"))
                .andExpect(jsonPath("$.mode").value("standalone"))
                .andExpect(jsonPath("$.servingBaseUrl").value("https://api.example.com/data"));
    }
}
