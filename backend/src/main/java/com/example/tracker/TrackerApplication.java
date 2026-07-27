package com.example.tracker;

import com.example.tracker.model.Assignment;
import com.example.tracker.repository.AssignmentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The entry point. Running main() starts the embedded web server on port 8080.
 */
@SpringBootApplication
public class TrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }

    /**
     * A CommandLineRunner runs once at startup. Here we insert two sample rows
     * so the H2 in-memory database isn't empty when Angular first loads.
     *
     * The count() check matters: today the database is in-memory and is wiped on
     * every shutdown, so seeding blindly happens to be harmless. The moment this
     * points at a real, persistent database, an unguarded seed would re-insert
     * these two rows on EVERY restart and quietly pile up duplicates. Seeding
     * only when the table is empty makes the startup safe to repeat.
     */
    @Bean
    CommandLineRunner seedData(AssignmentRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            repository.save(new Assignment("Math Homework 1", "IN_PROGRESS"));
            repository.save(new Assignment("History Essay", "IN_PROGRESS"));
        };
    }
}
