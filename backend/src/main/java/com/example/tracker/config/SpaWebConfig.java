package com.example.tracker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SERVING THE ANGULAR SITE FROM THE SAME JAR
 * ------------------------------------------
 * The production frontend build uses relative URLs (environment.ts sets
 * apiBaseUrl to ''), so it expects to be served from the same origin as the
 * API. The deployment workflow copies that build into classpath:/static
 * before packaging, which Spring Boot already serves as files.
 *
 * What Boot does NOT do on its own is answer a deep link. A visitor who
 * refreshes on /admin/teachers asks the server for a file called
 * "admin/teachers", there is none, and the answer would be 404 - even though
 * the Angular router knows the page perfectly well. The resolver below answers
 * such a request with index.html instead, and the router takes it from there,
 * including drawing the app's own not-found page for a route it does not know.
 *
 * In development nothing is copied into static, so every lookup here finds
 * nothing and returns null - the same 404 as before this class existed. The
 * dev server on :4200 is still how the site is served while coding.
 *
 * Boot registers its own "/**" static handler only when nothing else has
 * claimed that pattern, so this replaces it rather than competing with it.
 * Controller mappings are consulted before any resource handler, so no API
 * route can be shadowed by a file of the same name.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new IndexFallbackResolver());
    }

    /**
     * A real file if there is one; index.html if the path is a client route;
     * otherwise nothing, which Spring turns into a 404.
     */
    static class IndexFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location)
                throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            if (!SpaRoutes.isClientRoute("/" + resourcePath)) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }
    }
}
