package com.example.tracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * WHO MAY DO WHAT (web layer, cross-cutting)
 * ------------------------------------------
 * Until this class existed the system had no notion of a user at all: anyone who
 * could reach the page had full control of a single shared list. That was a
 * recorded scope decision, not an oversight - but it is why nothing could be
 * "mine", and why edit and delete could not be built safely.
 *
 * The rules are declared here in one place. Scattering authorisation across the
 * controllers that happen to need it is how a system ends up with an endpoint
 * nobody remembered to protect.
 */
@Configuration
@EnableMethodSecurity   // enables @PreAuthorize on service methods
public class SecurityConfig {

    /**
     * Whether the OpenAPI description and its UI are reachable (US-27).
     *
     * Defaults to FALSE, so forgetting to set it fails closed. The development
     * profile turns it on in application.properties; a deployment that does not
     * set it gets no documentation endpoints at all.
     *
     * This flag exists because the paths below are permitAll. Every other route
     * in this application requires a session, and a documentation UI is exactly
     * the kind of convenience that quietly becomes an unauthenticated listing of
     * the entire API surface in production.
     */
    @org.springframework.beans.factory.annotation.Value("${app.openapi.enabled:false}")
    private boolean openApiEnabled;

    /**
     * Whether the cookies this application sets carry the Secure flag.
     *
     * Defaults to FALSE because development is served over plain http on
     * localhost, where a Secure cookie is never sent back at all - and the
     * symptom of getting that wrong is every write failing CSRF with nothing
     * in the log that says why. application-prod.properties turns it on.
     *
     * Note what the customizer below does with it: it only ever ADDS the flag,
     * never removes it. Spring Security's own default follows
     * request.isSecure(), so an HTTPS deployment that forgets this property
     * still gets a Secure cookie; setting it false explicitly would have taken
     * one away.
     */
    @org.springframework.beans.factory.annotation.Value("${app.cookies.secure:false}")
    private boolean secureCookies;

    /**
     * SameSite for those cookies. "Lax" is right whenever the browser reaches
     * the API and the site it came from on the same registrable domain, which
     * includes development (localhost:4200 and localhost:8080 differ by port,
     * and a port does not make a different SITE).
     *
     * It is a property rather than a literal because CORS exists precisely to
     * support the deployment where they are NOT the same site, and there
     * "Lax" would silently stop sending the session cookie on the API call -
     * an authentication failure with a cookie-policy cause, which is among the
     * harder things to diagnose from a 401. Such a deployment needs "None",
     * which browsers only honour together with Secure.
     */
    @org.springframework.beans.factory.annotation.Value("${app.cookies.same-site:Lax}")
    private String cookieSameSite;

    /**
     * The paths springdoc serves. Listed once here rather than inline, so the
     * set that is opened up is visible in a single place.
     */
    private static final String[] OPENAPI_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    /**
     * BCrypt, not a plain hash.
     *
     * BCrypt is deliberately SLOW and salts every password individually. Slow
     * defeats brute force; per-password salt means two people who choose the
     * same password still get different hashes, so cracking one reveals nothing
     * about the other. A fast hash like SHA-256 is the wrong tool here - being
     * fast is precisely the problem.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /** Exposed so AuthController can authenticate a sign-in attempt. */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Use the CorsConfig filter rather than Spring Security's own defaults.
            .cors(cors -> {})

            /*
             * CSRF PROTECTION IS ON.
             *
             * Authentication here rides on a session cookie, and browsers attach
             * cookies to requests regardless of which site triggered them. Without
             * CSRF protection, any page a signed-in user visits could quietly POST
             * to this API as them. Disabling CSRF "because it is an API" is only
             * safe when the API does not use cookies; this one does.
             *
             * The token goes into a readable XSRF-TOKEN cookie, which Angular's
             * HttpClient recognises and echoes back automatically as X-XSRF-TOKEN.
             * withHttpOnlyFalse is required for that - the client must be able to
             * read it. That is safe: the token defends against a DIFFERENT site
             * forging requests, not against script on our own page.
             */
            .csrf(csrf -> csrf
                    .csrfTokenRepository(csrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))

            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

            .authorizeHttpRequests(auth -> {
                    // Signing in and checking who you are must work before you are known.
                    auth.requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll();
                    /*
                     * Forgetting a password is by definition something you do
                     * while signed out, so both halves of the flow are open.
                     * Neither leaks: forgot-password answers identically
                     * whether or not the account exists (see
                     * PasswordResetService), and reset-password requires a
                     * 256-bit single-use token nobody can guess.
                     */
                    auth.requestMatchers("/api/auth/forgot-password",
                                         "/api/auth/reset-password").permitAll();
                    // Preflight is a browser mechanic, not a protected action.
                    auth.requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll();
                    // The documentation UI, only when explicitly switched on. The
                    // rule is added conditionally rather than being permitted and
                    // then blocked elsewhere: a path that is never permitted cannot
                    // be reached by a mistake in a later filter.
                    if (openApiEnabled) {
                        auth.requestMatchers(OPENAPI_PATHS).permitAll();
                    }
                    /*
                     * The health probe, and ONLY the health probe.
                     *
                     * A load balancer cannot sign in, so this one path has to
                     * be open. Note what is NOT written here: no
                     * "/actuator/**", and not even "/actuator/health/**" -
                     * that second form would expose /actuator/health/db, which
                     * reports per-component detail. Everything else actuator
                     * serves falls through to anyRequest().authenticated()
                     * below, so it fails closed even if the exposure list in
                     * application.properties is ever widened by accident.
                     *
                     * management.endpoint.health.show-details=never keeps the
                     * body to UP or DOWN, so this says whether the instance is
                     * serving without describing what it is connected to.
                     */
                    auth.requestMatchers("/actuator/health").permitAll();
                    // Everything else needs an account.
                    auth.anyRequest().authenticated();
            })

            /*
             * Answer an unauthenticated API call with 401 and nothing else.
             *
             * The default is a redirect to a login PAGE, which is right for a
             * server-rendered site and wrong here: a fetch() would receive 200 and
             * a chunk of HTML, and the frontend would try to parse it as JSON. A
             * bare 401 is unambiguous.
             */
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());   // handled by AuthController so it returns JSON

        return http.build();
    }

    /**
     * The CSRF token repository, built in its own method so the cookie's
     * attributes are readable rather than buried inside the filter chain.
     *
     * withHttpOnlyFalse is not negotiable here - Angular's HttpClient has to
     * READ XSRF-TOKEN to echo it back as X-XSRF-TOKEN, which is the whole
     * mechanism. Secure and SameSite are the two attributes that can be
     * tightened without breaking that, so they are the two that are settable.
     */
    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> {
            if (secureCookies) {
                cookie.secure(true);
            }
            cookie.sameSite(cookieSameSite);
        });
        return repository;
    }
}
