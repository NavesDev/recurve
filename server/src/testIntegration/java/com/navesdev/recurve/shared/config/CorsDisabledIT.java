package com.navesdev.recurve.shared.config;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** NFR-10, fail-closed: with no origin configured, no browser origin is allowed. */
@SpringBootTest
@AutoConfigureMockMvc
class CorsDisabledIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void noOriginIsAllowedByDefault() throws Exception {
        mvc.perform(CorsIT.preflight("http://localhost:5173"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
