package com.example.tracker.config;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * WHICH REQUESTS BELONG TO THE ANGULAR SITE RATHER THAN THE API
 * -------------------------------------------------------------
 * When the built Angular application is packaged into the jar (see
 * SpaWebConfig), one server answers both the API and the pages. Two pieces of
 * configuration then have to agree on where one ends and the other begins:
 *
 *   - SecurityConfig, which lets anybody DOWNLOAD the site. The page bundle is
 *     the same file for every visitor and contains no data; refusing it to a
 *     signed-out visitor would leave them with no login form to sign in on.
 *   - SpaWebConfig, which answers a deep link such as /admin/teachers with
 *     index.html so the Angular router can draw the page.
 *
 * The rule lives here, once, so those two can never drift apart. If they did,
 * the failure would be a page that is served but cannot be reached, or worse,
 * an API path that is suddenly open.
 *
 * WHY A LIST OF EXCLUSIONS AND NOT A LIST OF PAGES
 * ------------------------------------------------
 * The Angular routes change far more often than the server's own prefixes, and
 * a page added without updating a list here would 401 on a hard refresh - the
 * route-guard lesson in CLAUDE.md, repeated. The server prefixes, by contrast,
 * are few and stable. Everything under them keeps the existing rule:
 * anyRequest().authenticated() still applies to every one, so this class
 * cannot open an API path, however a new endpoint is named, as long as it sits
 * under /api.
 *
 * Only GET and HEAD qualify. A page is never POSTed to, so a write to a
 * non-API path is refused exactly as before.
 */
public final class SpaRoutes {

    /**
     * Prefixes the server answers itself. Nothing under these is ever treated
     * as part of the site, whatever the HTTP method.
     *
     * The springdoc paths are listed even though production switches them off:
     * if they were not, a GET to /v3/api-docs in production would be let
     * through here and answered with index.html - harmless, but it would make
     * "the documentation is off" depend on two settings instead of one.
     */
    static final List<String> SERVER_PREFIXES = List.of(
            "/api", "/actuator", "/v3/api-docs", "/swagger-ui", "/swagger-ui.html", "/error");

    private SpaRoutes() {
    }

    /** The RequestMatcher SecurityConfig permits. */
    public static boolean isSiteRequest(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !isServerPath(path);
    }

    /**
     * True for a path the server owns. "/api" and "/api/x" match; "/apiary"
     * does not, because a prefix is only a prefix at a path-segment boundary.
     */
    static boolean isServerPath(String path) {
        for (String prefix : SERVER_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a missing file should be answered with index.html.
     *
     * A last segment with a dot in it is a request for a FILE (main.js,
     * favicon.ico). If that file does not exist the honest answer is 404, not
     * an HTML page with a 200 - a browser that asked for a script and received
     * markup reports a confusing MIME error instead of a missing file.
     */
    static boolean isClientRoute(String path) {
        if (isServerPath(path)) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }
}
