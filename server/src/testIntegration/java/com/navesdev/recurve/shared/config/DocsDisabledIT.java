package com.navesdev.recurve.shared.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code recurve.docs.enabled} is one switch for the page, the contract
 * and the anonymous access to them: off, {@code /docs} is not mapped and
 * falls under the default rule, so a stranger gets a 401, not a 404.
 */
@SpringBootTest(properties = "recurve.docs.enabled=false")
@AutoConfigureMockMvc
class DocsDisabledIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void hidesEverythingBehindAuthentication() throws Exception {
        mvc.perform(get("/docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/docs/openapi.yaml")).andExpect(status().isUnauthorized());
        mvc.perform(get("/docs/ui/swagger-ui.css")).andExpect(status().isUnauthorized());
    }
}
