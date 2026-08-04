package com.example.tracker;

import com.example.tracker.model.AppUser;
import com.example.tracker.model.Assignment;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Role;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.model.Subject;
import com.example.tracker.model.Submission;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.AssignmentRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.SchoolClassRepository;
import com.example.tracker.repository.SubjectRepository;
import com.example.tracker.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;

/**
 * The entry point. Running main() starts the embedded web server on port 8080.
 */
@SpringBootApplication
public class TrackerApplication {

    private static final Logger log = LoggerFactory.getLogger(TrackerApplication.class);

    /** The shared password for every seeded development account. */
    private static final String DEV_PASSWORD = "password123";

    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }

    /**
     * Ensures the development accounts exist and have a usable password.
     *
     * The two original accounts are created by Flyway migration V2, because rows
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
    @Order(1)
    CommandLineRunner seedAccounts(AppUserRepository users, PasswordEncoder encoder) {
        return args -> {
            ensureAccount(users, encoder, "teacher", Role.TEACHER);
            ensureAccount(users, encoder, "student", Role.STUDENT);

            /*
             * A SECOND TEACHER AND TWO MORE STUDENTS EXIST FOR A REASON.
             *
             * With one teacher and one student, three of the requirements this
             * system claims to meet are unobservable: a student taught by
             * several teachers, a teacher running several classes, and an
             * assignment reaching a whole class rather than a person. The demo
             * data would have looked fine and demonstrated none of it.
             *
             * Seed data that cannot exercise the model is seed data that hides
             * defects, so the roster below is the smallest one in which every
             * relationship is visible.
             */
            ensureAccount(users, encoder, "teacher2", Role.TEACHER);
            ensureAccount(users, encoder, "student2", Role.STUDENT);
            ensureAccount(users, encoder, "student3", Role.STUDENT);
        };
    }

    private void ensureAccount(AppUserRepository users, PasswordEncoder encoder,
                               String username, Role role) {
        users.findByUsername(username).ifPresentOrElse(existing -> {
            if (!encoder.matches(DEV_PASSWORD, existing.getPasswordHash())) {
                existing.setPasswordHash(encoder.encode(DEV_PASSWORD));
                users.save(existing);
                log.info("Reset development password for '{}'", username);
            }
        }, () -> {
            users.save(new AppUser(username, encoder.encode(DEV_PASSWORD), role));
            log.info("Created development account '{}' with role {}", username, role);
        });
    }

    /**
     * The timetable: subjects, classes, who is in them, and who teaches what.
     *
     * EVERY LINE BELOW EARNS ITS PLACE by making one requirement observable:
     *
     *   teacher  takes Mathematics for 10A AND 10B  - one teacher, several classes
     *   teacher  also takes History for 10A         - one teacher, several subjects
     *   teacher2 takes Science for 10A              - so 10A has TWO teachers
     *   student  is in 10A                          - so student has two teachers
     *                                                 and three subjects
     *
     * Each step checks for what it is about to create, so a restart against the
     * persistent database changes nothing. That guard is not optional here: the
     * database no longer forgets between runs, and a seed that inserted blindly
     * would pile up duplicate classes on every start.
     */
    @Bean
    @Order(2)
    CommandLineRunner seedTimetable(SubjectRepository subjects,
                                    SchoolClassRepository classes,
                                    EnrolmentRepository enrolments,
                                    CourseRepository courses,
                                    AppUserRepository users) {
        return args -> {
            Subject maths = ensureSubject(subjects, "MATH", "Mathematics");
            Subject science = ensureSubject(subjects, "SCI", "Science");
            Subject history = ensureSubject(subjects, "HIST", "History");

            SchoolClass tenA = ensureClass(classes, "Grade 10A");
            SchoolClass tenB = ensureClass(classes, "Grade 10B");

            AppUser teacher = require(users, "teacher");
            AppUser teacher2 = require(users, "teacher2");
            AppUser student = require(users, "student");
            AppUser student2 = require(users, "student2");
            AppUser student3 = require(users, "student3");

            ensureEnrolment(enrolments, student, tenA);
            ensureEnrolment(enrolments, student2, tenA);
            ensureEnrolment(enrolments, student3, tenB);

            ensureCourse(courses, maths, tenA, teacher);
            ensureCourse(courses, maths, tenB, teacher);
            ensureCourse(courses, history, tenA, teacher);
            ensureCourse(courses, science, tenA, teacher2);

            log.info("Timetable ready: {} subjects, {} classes, {} courses",
                    subjects.count(), classes.count(), courses.count());
        };
    }

    /**
     * Sample work, so the lists are not empty on a fresh database.
     *
     * Each assignment fans out to every student in its course's class, exactly
     * as the service does when a teacher sets work through the interface. Doing
     * it the same way here matters: seed data built by a different route would
     * be seed data that does not prove the real one works.
     */
    @Bean
    @Order(3)
    CommandLineRunner seedAssignments(AssignmentRepository assignments,
                                      SubmissionRepository submissions,
                                      EnrolmentRepository enrolments,
                                      CourseRepository courses,
                                      SubjectRepository subjects,
                                      SchoolClassRepository classes,
                                      AppUserRepository users) {
        return args -> {
            if (assignments.count() > 0) {
                return;
            }

            AppUser teacher = require(users, "teacher");
            AppUser teacher2 = require(users, "teacher2");

            Course mathsTenA = requireCourse(courses, subjects, classes, "MATH", "Grade 10A", teacher);
            Course historyTenA = requireCourse(courses, subjects, classes, "HIST", "Grade 10A", teacher);
            Course scienceTenA = requireCourse(courses, subjects, classes, "SCI", "Grade 10A", teacher2);

            setWork(assignments, submissions, enrolments,
                    "Algebra worksheet 1",
                    "Questions 1 to 20. Show your working.",
                    mathsTenA, teacher, LocalDate.now().plusDays(7));

            setWork(assignments, submissions, enrolments,
                    "Essay: the Industrial Revolution",
                    "1500 words. Cite at least three sources.",
                    historyTenA, teacher, LocalDate.now().plusDays(14));

            // Deliberately dated in the past, so the OVERDUE state is visible
            // immediately rather than only after somebody waits for a deadline.
            setWork(assignments, submissions, enrolments,
                    "Lab report: reaction rates",
                    "Include your method, results table and conclusion.",
                    scienceTenA, teacher2, LocalDate.now().minusDays(3));

            log.info("Seeded {} assignments and {} submissions",
                    assignments.count(), submissions.count());
        };
    }

    // ----- idempotent helpers --------------------------------------------------

    private Subject ensureSubject(SubjectRepository subjects, String code, String name) {
        return subjects.findByCodeIgnoreCase(code)
                .orElseGet(() -> subjects.save(new Subject(code, name)));
    }

    private SchoolClass ensureClass(SchoolClassRepository classes, String name) {
        return classes.findByNameIgnoreCase(name)
                .orElseGet(() -> classes.save(new SchoolClass(name)));
    }

    private void ensureEnrolment(EnrolmentRepository enrolments,
                                 AppUser student, SchoolClass schoolClass) {
        if (!enrolments.existsByStudentAndSchoolClass(student, schoolClass)) {
            enrolments.save(new Enrolment(student, schoolClass));
        }
    }

    private void ensureCourse(CourseRepository courses, Subject subject,
                              SchoolClass schoolClass, AppUser teacher) {
        if (courses.findBySubjectAndSchoolClassAndTeacher(subject, schoolClass, teacher).isEmpty()) {
            courses.save(new Course(subject, schoolClass, teacher));
        }
    }

    /**
     * Create an assignment and give every enrolled student their own submission.
     *
     * The same fan-out AssignmentService performs. If this ever drifts from that
     * one, the seeded data stops resembling data the application would produce -
     * and tests written against it would be testing a fiction.
     */
    private void setWork(AssignmentRepository assignments,
                         SubmissionRepository submissions,
                         EnrolmentRepository enrolments,
                         String title, String description,
                         Course course, AppUser teacher, LocalDate dueDate) {

        Assignment assignment = assignments.save(
                new Assignment(title, description, course, teacher, dueDate));

        List<Enrolment> register =
                enrolments.findBySchoolClassOrderByIdAsc(course.getSchoolClass());

        for (Enrolment enrolment : register) {
            submissions.save(new Submission(assignment, enrolment.getStudent()));
        }
    }

    private AppUser require(AppUserRepository users, String username) {
        return users.findByUsername(username).orElseThrow(() -> new IllegalStateException(
                "Account '" + username + "' is missing; seedAccounts must run first."));
    }

    /**
     * Look up a seeded course, failing loudly if the previous runner did not
     * create it.
     *
     * The @Order annotations above are not decoration. Under the test profile
     * Flyway is disabled and these runners are the ONLY thing that creates this
     * data, at which point an undeclared ordering dependency became a
     * NoSuchElementException with no explanation. Stating the order makes the
     * dependency real rather than accidental, and this message makes a breach of
     * it say so.
     */
    private Course requireCourse(CourseRepository courses, SubjectRepository subjects,
                                 SchoolClassRepository classes,
                                 String subjectCode, String className, AppUser teacher) {
        Subject subject = subjects.findByCodeIgnoreCase(subjectCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Subject '" + subjectCode + "' is missing; seedTimetable must run first."));
        SchoolClass schoolClass = classes.findByNameIgnoreCase(className)
                .orElseThrow(() -> new IllegalStateException(
                        "Class '" + className + "' is missing; seedTimetable must run first."));

        return courses.findBySubjectAndSchoolClassAndTeacher(subject, schoolClass, teacher)
                .orElseThrow(() -> new IllegalStateException(
                        "Course " + subjectCode + "/" + className + " is missing; "
                                + "seedTimetable must run first."));
    }
}
