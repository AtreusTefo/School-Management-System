package com.example.tracker.config;

import com.example.tracker.TrackerApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE TWO THINGS THAT KEEP A PRODUCTION DEPLOYMENT FROM STARTING WRONG.
 *
 * Both were checked by hand when they were written - the prod profile was
 * started against the real SQL Server, reported "Successfully validated 18
 * migrations", and seeded nothing. Neither of those observations survives into
 * next month, which is the problem these tests exist to solve:
 *
 *   1. An un-gated seed runner would recreate 'admin'/'teacher'/'student' with
 *      the password literal in TrackerApplication - in production, on every
 *      start, resetting anyone who had changed it. It is the kind of mistake
 *      that is invisible in review, because a new @Bean CommandLineRunner
 *      looks exactly like the four that are already there.
 *   2. ProductionConfigCheck.REQUIRED is a hand-maintained copy of which
 *      placeholders in application-prod.properties have no default, and its
 *      own comment admits nothing enforces the two staying in step. This
 *      enforces it.
 *
 * Plain JUnit and reflection, deliberately: the prod profile cannot be started
 * inside the suite (it requires a real SQL Server and refuses every default),
 * so what is asserted here is the DECLARATION, which is the thing that would
 * actually be got wrong.
 */
class ProductionProfileTest {

    @Test
    @DisplayName("every seed runner is gated out of production")
    void everySeedRunnerIsGatedOutOfProduction() {
        List<String> ungated = new ArrayList<>();

        for (Method method : TrackerApplication.class.getDeclaredMethods()) {
            boolean isSeedRunner = method.isAnnotationPresent(Bean.class)
                    && CommandLineRunner.class.isAssignableFrom(method.getReturnType());
            if (!isSeedRunner) {
                continue;
            }
            Profile profile = method.getAnnotation(Profile.class);
            if (profile == null || !List.of(profile.value()).contains("!prod")) {
                ungated.add(method.getName());
            }
        }

        assertThat(ungated)
                .as("a @Bean CommandLineRunner in TrackerApplication with no @Profile(\"!prod\") "
                        + "would seed development accounts into production")
                .isEmpty();
    }

    @Test
    @DisplayName("the seed runners are actually being found, so the gate test cannot pass vacuously")
    void theGateTestIsNotVacuous() {
        long seedRunners = List.of(TrackerApplication.class.getDeclaredMethods()).stream()
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .filter(m -> CommandLineRunner.class.isAssignableFrom(m.getReturnType()))
                .count();

        assertThat(seedRunners)
                .as("if a rename or refactor left this at zero, the gate test above would "
                        + "pass while checking nothing at all")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("every defaulted-nothing placeholder in application-prod.properties is required")
    void everyBarePlaceholderIsChecked() throws IOException {
        Path file = Path.of("src/main/resources/application-prod.properties");
        assertThat(file).exists();

        String text = Files.readString(file, StandardCharsets.UTF_8);

        /*
         * A placeholder with no ":default" inside it - ${DB_URL} but not
         * ${app.cookies.secure}, which is a property this file sets two lines
         * earlier rather than something the environment must supply. The two
         * are told apart by case: environment variables are the upper-case
         * ones, and that is the convention this file follows throughout.
         */
        Pattern bare = Pattern.compile("[$][{]([A-Z][A-Z0-9_]*)[}]");

        List<String> unchecked = new ArrayList<>();
        Matcher matcher = bare.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!ProductionConfigCheck.REQUIRED.contains(name) && !unchecked.contains(name)) {
                unchecked.add(name);
            }
        }

        assertThat(unchecked)
                .as("these have no default, so an unset one either fails with a driver-level "
                        + "message or is passed through as literal text - add them to "
                        + "ProductionConfigCheck.REQUIRED so the failure names them")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing is required that the production configuration does not actually use")
    void nothingIsRequiredThatIsNeverRead() throws IOException {
        String text = Files.readString(
                Path.of("src/main/resources/application-prod.properties"), StandardCharsets.UTF_8);

        for (String required : ProductionConfigCheck.REQUIRED) {
            assertThat(text)
                    .as("%s is demanded at startup but never read - either it is a leftover, "
                            + "or the property that should use it is missing", required)
                    .contains("${" + required + "}");
        }
    }
}
