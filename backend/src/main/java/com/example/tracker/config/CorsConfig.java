package com.example.tracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CROSS-ORIGIN CONFIGURATION (web layer, cross-cutting)
 * -----------------------------------------------------
 * Browsers refuse to let a page served from one origin call an API on another
 * unless the API says it is allowed. In development the page is served from
 * :4200 and the API from :8080, so that permission has to be granted explicitly.
 *
 * WHY THIS LIVES HERE AND NOT ON THE CONTROLLER
 * ---------------------------------------------
 * The allowed origins used to be written into @CrossOrigin on
 * AssignmentController. That compiled the FRONTEND'S ADDRESS INTO THE BACKEND:
 * moving the interface to a different host meant editing Java and rebuilding a
 * jar, which is not a property a deployable service should have.
 *
 * Reading the list from configuration instead means the same artefact runs in
 * any environment. Override it without touching the build:
 *
 *   --app.cors.allowed-origins=https://tracker.example.com     (command line)
 *   APP_CORS_ALLOWED_ORIGINS=https://tracker.example.com       (environment)
 *
 * Which origins are permitted is also a security decision, and security
 * decisions belong somewhere deliberate rather than scattered across the
 * controllers that happen to need them.
 *
 * WHY A FILTER RATHER THAN WebMvcConfigurer
 * -----------------------------------------
 * The obvious implementation is `implements WebMvcConfigurer` and overriding
 * addCorsMappings. Spring declares that method's parameter as @NonNull, so an
 * override has to repeat the annotation or the compiler's null analysis reports
 * an unchecked conversion on every build. Registering a CorsFilter overrides
 * nothing, so the contract cannot be broken and the warning cannot occur.
 *
 * A filter also runs earlier in the chain than MVC's own CORS handling, so
 * preflight requests are answered before anything else inspects them - which
 * matters if authentication is ever added in front of the controllers.
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    private final List<String> allowedOrigins;

    /**
     * Spring splits a comma-separated property for us, so
     * `app.cors.allowed-origins=a,b` arrives here as two entries.
     */
    public CorsConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = Arrays.asList(allowedOrigins);
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // allowCredentials is deliberately left off: this API has no cookies or
        // sessions, and enabling it would forbid a wildcard origin later anyway.

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        // Logged at startup so the origins actually in force are visible without
        // guessing which configuration file or environment variable won.
        log.info("CORS: allowing origins {} on /api/**", allowedOrigins);
        return new CorsFilter(source);
    }
}
