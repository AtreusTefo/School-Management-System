package com.example.tracker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the timer on - everywhere except the test profile.
 *
 * WHY @EnableScheduling IS HERE AND NOT ON TrackerApplication
 * ----------------------------------------------------------
 * On the application class it would be unconditional, and the due-date
 * reminder job would then fire in the middle of the test suite. That is not a
 * theoretical tidiness problem: the suite shares one H2 database across test
 * classes (see the Spring TestContext caching note in CLAUDE.md), so a sweep
 * running at an arbitrary moment would raise real notifications against the
 * seeded students, and any test asserting "this student has one notification"
 * would pass or fail depending on when the timer happened to fire. That is the
 * exact shape of the shared-mutable-state failures this project has already
 * been bitten by twice.
 *
 * Excluding the profile rather than merely setting a long initial delay is the
 * stronger choice: a delay is a bet that the suite finishes first, and the
 * suite gets slower over time. With no scheduling registered at all there is
 * nothing to race.
 *
 * The job itself is still a bean under test, and DueDateReminderJobTest calls
 * sendDueSoonReminders() directly. That is the better thing to test anyway -
 * whether the sweep is idempotent is a property of the sweep, not of the timer
 * that happens to invoke it.
 */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
