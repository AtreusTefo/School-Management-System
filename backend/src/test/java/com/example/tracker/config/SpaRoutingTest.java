package com.example.tracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Serving the packaged Angular site: SpaRoutes, SpaWebConfig and the one rule
 * SecurityConfig gained for them.
 *
 * src/test/resources/static/index.html stands in for the real build, which
 * only exists in the deployment workflow. Its marker text is what proves a
 * deep link was answered with the page rather than with something else.
 *
 * The half of this that matters for security is the second nested class: the
 * new permitAll rule must not have opened anything under /api, /actuator or
 * the documentation paths, and must not accept a write anywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaRoutingTest {

    private static final String MARKER = "spa-routing-test-index";

    @Autowired
    private MockMvc mvc;

    @Nested
    @DisplayName("the site is served signed out")
    class Site {

        @Test
        @DisplayName("a deep link is answered with index.html so the Angular router can draw it")
        void deepLinkGetsTheIndexPage() throws Exception {
            mvc.perform(get("/admin/teachers"))
               .andExpect(status().isOk())
               .andExpect(content().string(containsString(MARKER)));
        }

        @Test
        @DisplayName("a missing FILE is a 404, not an HTML page")
        void missingFileIs404() throws Exception {
            mvc.perform(get("/no-such-bundle.js"))
               .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("nothing the server owns was opened up")
    class ServerPathsStayClosed {

        @Test
        @DisplayName("an unknown API path is still 401 for an anonymous caller, not the index page")
        void apiStillNeedsASession() throws Exception {
            mvc.perform(get("/api/no-such-endpoint"))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("actuator paths other than health still need a session")
        void actuatorStillNeedsASession() throws Exception {
            mvc.perform(get("/actuator/info"))
               .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a write to a page path is refused, because pages are only ever read")
        void writeToAPagePathIsRefused() throws Exception {
            mvc.perform(post("/admin/teachers").with(csrf()))
               .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("SpaRoutes, the rule both configurations share")
    class Rule {

        @Test
        @DisplayName("server prefixes match only at a path-segment boundary")
        void prefixesMatchOnSegmentBoundaries() {
            assertThat(SpaRoutes.isServerPath("/api")).isTrue();
            assertThat(SpaRoutes.isServerPath("/api/assignments")).isTrue();
            assertThat(SpaRoutes.isServerPath("/actuator/health")).isTrue();
            assertThat(SpaRoutes.isServerPath("/swagger-ui.html")).isTrue();
            assertThat(SpaRoutes.isServerPath("/v3/api-docs")).isTrue();
            assertThat(SpaRoutes.isServerPath("/apiary")).isFalse();
            assertThat(SpaRoutes.isServerPath("/admin/teachers")).isFalse();
        }

        @Test
        @DisplayName("only an extension-less, non-server path falls back to index.html")
        void clientRoutes() {
            assertThat(SpaRoutes.isClientRoute("/")).isTrue();
            assertThat(SpaRoutes.isClientRoute("/admin/teachers/12")).isTrue();
            assertThat(SpaRoutes.isClientRoute("/main-ABC123.js")).isFalse();
            assertThat(SpaRoutes.isClientRoute("/api/assignments")).isFalse();
        }
    }
}
