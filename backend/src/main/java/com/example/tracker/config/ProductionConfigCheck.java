package com.example.tracker.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;

/**
 * REFUSES TO START a production deployment that has not been configured.
 *
 * WHY THIS EXISTS RATHER THAN JUST THE PLACEHOLDERS.
 * ---------------------------------------------------
 * application-prod.properties deliberately writes every secret as a bare
 * ${DB_URL}-style placeholder with no ":default" suffix, so that a missing
 * value cannot silently fall back to the development one in the shared file.
 * That much works: an unset variable never yields the development database,
 * the development password, or a mailer that logs reset links instead of
 * sending them.
 *
 * What it does NOT do is say so clearly, and the parts of Spring involved
 * behave differently in ways worth writing down because they are surprising:
 *
 *   - A property read through @Value (app.cors.allowed-origins, in CorsConfig)
 *     THROWS PlaceholderResolutionException naming the variable. Good message.
 *   - A property bound through @ConfigurationProperties (every
 *     spring.datasource.* value, via Boot's Binder) resolves placeholders with
 *     ignoreUnresolvablePlaceholders = true, so an unset one is passed along
 *     AS THE LITERAL TEXT "${DB_URL}". That still fails - measured, not
 *     assumed - but it fails as "Driver ... claims to not accept jdbcUrl,
 *     ${DB_URL}" forty lines into a Hikari stack trace. An unset DB_PASSWORD
 *     is worse: the password becomes the literal text and SQL Server answers
 *     with an ordinary login failure, which reads exactly like a wrong
 *     password and sends whoever is deploying to look at the database.
 *
 * So this is the project's usual division applied to configuration rather than
 * to data: the placeholders are the guarantee, and this is the message.
 *
 * WHY AN EnvironmentPostProcessor AND NOT A BEAN
 * ----------------------------------------------
 * It has to run before ANYTHING ELSE can fail for the same underlying reason,
 * or the confusing failure happens first and this never gets to speak.
 *
 * The first implementation was a static @Bean BeanFactoryPostProcessor, which
 * is early enough to beat the DataSource and Flyway - and was not early enough
 * in practice. Adding MAIL_HOST to the list below broke it immediately:
 * MailSenderAutoConfiguration carries a condition on spring.mail.host, and
 * conditions are evaluated by ConfigurationClassPostProcessor, which is itself
 * a BeanFactoryPostProcessor that runs first. The result was a placeholder
 * error about MAIL_HOST alone, and the other five missing variables were never
 * mentioned.
 *
 * An EnvironmentPostProcessor runs while the Environment is being prepared,
 * before any configuration class is parsed or any condition evaluated, so
 * there is nothing left that can get in first. LOWEST_PRECEDENCE puts it after
 * ConfigDataEnvironmentPostProcessor, which is what loads
 * application-prod.properties and settles the active profiles - this must see
 * the finished Environment, not a half-built one.
 *
 * Registered in META-INF/spring.factories, because a class Spring instantiates
 * this early cannot be found by component scanning: scanning has not happened
 * yet and is one of the things being protected.
 *
 * Every missing variable is collected and reported together, rather than one
 * per restart.
 */
public class ProductionConfigCheck implements EnvironmentPostProcessor, Ordered {

    /**
     * The environment variables application-prod.properties leaves without a
     * default. If you add another bare placeholder to that file, add it here
     * in the same change - this list is not derived from it.
     *
     * Package-private rather than private because ProductionProfileTest reads
     * it, and asserts the two stay in step in BOTH directions: nothing bare in
     * that file is missing from this list, and nothing in this list is demanded
     * at startup without being read.
     */
    static final List<String> REQUIRED = List.of(
            "DB_URL",
            "DB_USERNAME",
            "DB_PASSWORD",
            "APP_CORS_ALLOWED_ORIGINS",
            "MAIL_HOST",
            "RESET_LINK_BASE");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED) {
            String value = environment.getProperty(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The 'prod' profile is active but these environment variables are "
                            + "not set: " + String.join(", ", missing)
                            + ". They have no defaults on purpose - a production "
                            + "deployment must not inherit the development database, "
                            + "password, allowed origin or mail settings from "
                            + "application.properties. Set them and start again.");
        }
    }

    /**
     * After ConfigDataEnvironmentPostProcessor, so application-prod.properties
     * has been loaded and the active profiles are settled before this looks.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
