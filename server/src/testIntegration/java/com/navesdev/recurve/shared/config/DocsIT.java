package com.navesdev.recurve.shared.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With {@code recurve.docs.enabled} on, the page, its assets and the
 * contract are served to anyone. {@link DocsDisabledIT} covers off.
 */
@SpringBootTest(properties = "recurve.docs.enabled=true")
@AutoConfigureMockMvc
class DocsIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void servesTheSwaggerUiToAnyone() throws Exception {
        mvc.perform(get("/docs")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/docs/"));

        mvc.perform(get("/docs/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SwaggerUIBundle")));

        mvc.perform(get("/docs/ui/swagger-ui.css")).andExpect(status().isOk());
    }

    @Test
    void servesTheContractToAnyone() throws Exception {
        mvc.perform(get("/docs/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith("openapi:")));
    }
}
