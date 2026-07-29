package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.AssignmentStatus;
import com.example.tracker.model.Role;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.AssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;

/**
 * The entry point. Running main() starts the embedded web server on port 8080.
 */
@SpringBootApplication
public class TrackerApplication {

    private static final Logger log = LoggerFactory.getLogger(TrackerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }

    /**
     * Ensures the two development accounts exist and have a usable password.
     *
     * The accounts themselves are created by Flyway migration V2, because rows
     * that other tables point at are part of the schema's starting state. What
     * that migration CANNOT do is hash a password correctly - a hash literal in
     * SQL is a hostage to whatever cost factor was current when it was written.
     * So the migration inserts a placeholder and this runner sets a real BCrypt
     * hash using the application's own encoder.
     *
     * It only writes when the stored value does not already verify, so restarts
     * are idempotent and a changed password is not silently reset.
     */
    @Bean
    @Order(1)   // must run before seedAssignments, which needs these accounts
    CommandLineRunner seedAccounts(AppUserRepository users, PasswordEncoder encoder) {
        return args -> {
            ensureAccount(users, encoder, "teacher", Role.TEACHER);
            ensureAccount(users, encoder, "student", Role.STUDENT);
        };
    }

    private void ensureAccount(AppUserRepository users, PasswordEncoder encoder,
                               String username, Role role) {
        final String devPassword = "password123";

        users.findByUsername(username).ifPresentOrElse(existing -> {
            if (!encoder.matches(devPassword, existing.getPasswordHash())) {
                existing.setPasswordHash(encoder.encode(devPassword));
                users.save(existing);
                log.info("Reset development password for '{}'", username);
            }
        }, () -> {
            users.save(new AppUser(username, encoder.encode(devPassword), role));
            log.info("Created development account '{}' with role {}", username, role);
        });
    }

    /**
     * Sample assignments, so the list is not empty on a fresh database.
     *
     * The count() check matters. The database is now PERSISTENT: without this
     * guard every restart would insert these rows again and quietly pile up
     * duplicates. Seeding only when the table is empty makes startup safe to
     * repeat - which was harmless to get wrong on the old in-memory database and
     * is not harmless now.
     */
    @Bean
    @Order(2)   // depends on seedAccounts having already run
    CommandLineRunner seedAssignments(AssignmentRepository assignments, AppUserRepository users) {
        return args -> {
            if (assignments.count() > 0) {
                return;
            }
            /*
             * The @Order above is not decoration. Against SQL Server the accounts
             * arrive with Flyway migration V2, so this runner found them whatever
             * order the beans happened to be created in. Under the test profile
             * Flyway is disabled and seedAccounts is the only thing that creates
             * them - at which point an undeclared ordering dependency became a
             * NoSuchElementException with no explanation. Stating the order makes
             * the dependency real rather than accidental.
             */
            AppUser teacher = users.findByUsername("teacher").orElseThrow(
                    () -> new IllegalStateException(
                            "Account 'teacher' is missing; seedAccounts must run first."));
            AppUser student = users.findByUsername("student").orElseThrow(
                    () -> new IllegalStateException(
                            "Account 'student' is missing; seedAccounts must run first."));

            // Work set FOR the student, so signing in as 'student' shows a
            // meaningful list rather than an empty one.
            assignments.save(new Assignment(
                    "Math Homework 1", AssignmentStatus.IN_PROGRESS, student, null));
            assignments.save(new Assignment(
                    "History Essay", AssignmentStatus.IN_PROGRESS, student,
                    LocalDate.now().plusDays(7)));
            // Deliberately dated in the past so the OVERDUE state is visible
            // immediately, without anyone having to wait for a deadline to pass.
            assignments.save(new Assignment(
                    "Science Lab Report", AssignmentStatus.IN_PROGRESS, student,
                    LocalDate.now().minusDays(3)));
            // One the teacher keeps, so the two roles show different lists.
            assignments.save(new Assignment(
                    "Prepare end-of-term report", AssignmentStatus.IN_PROGRESS, teacher,
                    LocalDate.now().plusDays(14)));

            log.info("Seeded {} sample assignments", assignments.count());
        };
    }
}
