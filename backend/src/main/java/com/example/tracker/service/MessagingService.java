package com.example.tracker.service;

import com.example.tracker.dto.ConversationView;
import com.example.tracker.dto.CorrespondentView;
import com.example.tracker.dto.MessageView;
import com.example.tracker.exception.AccessDeniedException;
import com.example.tracker.exception.ResourceNotFoundException;
import com.example.tracker.model.AppUser;
import com.example.tracker.model.Course;
import com.example.tracker.model.Enrolment;
import com.example.tracker.model.Message;
import com.example.tracker.model.NotificationType;
import com.example.tracker.model.PersonProfile;
import com.example.tracker.model.Role;
import com.example.tracker.model.SchoolClass;
import com.example.tracker.repository.AppUserRepository;
import com.example.tracker.repository.CourseRepository;
import com.example.tracker.repository.EnrolmentRepository;
import com.example.tracker.repository.MessageRepository;
import com.example.tracker.repository.PersonProfileRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MESSAGES BETWEEN PEOPLE - and the one rule that decides who may write to whom.
 *
 * THE RULE, AND WHY IT IS HERE AND NOT IN THE DATABASE
 * ---------------------------------------------------
 * A school is not a social network. Left unrestricted, this table would let
 * any student write to any other student in the school, privately, with the
 * institution's name on it - which is a safeguarding problem, not a feature
 * gap. So:
 *
 *   ADMIN    may write to anybody.
 *   TEACHER  may write to the students in the classes they teach, to any
 *            other teacher, and to any admin.
 *   STUDENT  may write to the teachers who actually teach them, and to any
 *            admin. NOT to another student.
 *
 * None of that is expressible as a CHECK constraint: every clause needs to
 * read Course or Enrolment, and a CHECK cannot see another table. So this
 * joins the small, explicitly-listed set of rules this system enforces in Java
 * alone - the three attendance rules, and no-overlapping-terms. Everything the
 * database CAN enforce about a message, it does: both ends are real accounts
 * with pinned roles, nobody writes to themselves, the body is not blank, and a
 * read time cannot precede the send.
 *
 * ONE DEFINITION, TWO USES. {@link #requireMayWriteTo} refuses a send, and
 * {@link #listCorrespondents} builds the compose form's list. The second is
 * written in terms of the first - it proposes candidates and filters them
 * through the same predicate - rather than as a parallel query with the same
 * rule expressed a second way. Two implementations would eventually disagree,
 * and the visible symptom would be a form offering a name that the send path
 * then refuses.
 *
 * SENDING RAISES A NOTIFICATION, in the same transaction, so an inbox and its
 * badge cannot disagree about whether a message arrived.
 */
@Service
@Transactional(readOnly = true)
public class MessagingService {

    private final MessageRepository messages;
    private final AppUserRepository users;
    private final CourseRepository courses;
    private final EnrolmentRepository enrolments;
    private final PersonProfileRepository profiles;
    private final AppUserService accounts;
    private final NotificationService notifications;

    public MessagingService(MessageRepository messages,
                            AppUserRepository users,
                            CourseRepository courses,
                            EnrolmentRepository enrolments,
                            PersonProfileRepository profiles,
                            AppUserService accounts,
                            NotificationService notifications) {
        this.messages = messages;
        this.users = users;
        this.courses = courses;
        this.enrolments = enrolments;
        this.profiles = profiles;
        this.accounts = accounts;
        this.notifications = notifications;
    }

    // =====================================================================
    // WRITING
    // =====================================================================

    /** Send a message, if the rule above allows it. */
    @Transactional
    public MessageView send(Long recipientId, String body) {
        AppUser me = accounts.currentActiveUser();
        AppUser them = requireAccount(recipientId);

        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A message needs something in it.");
        }
        if (body.trim().length() > 2000) {
            throw new IllegalArgumentException(
                    "A message cannot be longer than 2000 characters.");
        }
        /*
         * Checked before the permission rule on purpose. "You cannot send a
         * message to yourself" is a clearer answer than "you are not allowed
         * to write to that person", and under the rules above an admin writing
         * to themselves would otherwise be permitted right up to the database.
         */
        if (me.getId().equals(them.getId())) {
            throw new IllegalArgumentException("You cannot send a message to yourself.");
        }
        requireMayWriteTo(me, them);

        Message saved = messages.save(new Message(me, them, body));

        /*
         * The recipient is told in the same transaction. If this failed and the
         * message survived, the message would sit in an inbox with nothing to
         * announce it; if the message failed, there must be no notification
         * about a message that was never sent. One operation, one transaction -
         * see NotificationService.raise for why it deliberately does not open
         * its own.
         */
        notifications.raise(them, NotificationType.MESSAGE_RECEIVED,
                "New message from " + me.getUsername(),
                summarise(saved.getBody()),
                "MESSAGE", saved.getId(),
                "MESSAGE:" + saved.getId());

        return MessageView.of(saved);
    }

    /** Mark one received message as read. */
    @Transactional
    public MessageView markRead(Long id) {
        AppUser me = accounts.currentActiveUser();
        Message message = requireMessage(id);
        /*
         * Only the RECIPIENT can mark a message read - the sender marking their
         * own sent message read would make "have they seen it yet" a lie.
         * 404 rather than 403 for anything else, for the reason
         * NotificationService.requireOwn gives.
         */
        if (!message.getRecipient().getId().equals(me.getId())) {
            throw new ResourceNotFoundException("No message found with id = " + id);
        }
        message.markRead();
        return MessageView.of(messages.save(message));
    }

    // =====================================================================
    // READING
    // =====================================================================

    /** Everything sent to the caller, newest first. */
    public List<MessageView> inbox() {
        return messages.findByRecipientOrderBySentAtDescIdDesc(accounts.currentActiveUser())
                .stream().map(MessageView::of).toList();
    }

    /** Everything the caller has sent, newest first. */
    public List<MessageView> sent() {
        return messages.findBySenderOrderBySentAtDescIdDesc(accounts.currentActiveUser())
                .stream().map(MessageView::of).toList();
    }

    /** The caller's badge. */
    public long unreadCount() {
        return messages.countByRecipientAndReadAtIsNull(accounts.currentActiveUser());
    }

    /**
     * One conversation, oldest first.
     *
     * No permission check beyond "the caller is one of the two participants",
     * which the query itself enforces by construction - it only ever selects
     * rows where the caller is the sender or the recipient. Reading a
     * conversation with somebody the caller may no longer WRITE to is
     * deliberately still allowed: a student who changes class does not lose
     * what their previous teacher already said to them.
     */
    public List<MessageView> conversationWith(Long personId) {
        AppUser me = accounts.currentActiveUser();
        AppUser them = requireAccount(personId);
        return messages.conversation(me, them).stream().map(MessageView::of).toList();
    }

    /**
     * Everybody the caller has corresponded with, most recent first, DERIVED
     * from the messages themselves - see ConversationView for why this is not
     * a stored table.
     */
    public List<ConversationView> conversations() {
        AppUser me = accounts.currentActiveUser();

        // Newest first from both directions, so the first time a person is
        // seen is their latest message and nothing needs re-sorting after.
        List<Message> all = new ArrayList<>();
        all.addAll(messages.findByRecipientOrderBySentAtDescIdDesc(me));
        all.addAll(messages.findBySenderOrderBySentAtDescIdDesc(me));
        all.sort((a, b) -> {
            int byTime = b.getSentAt().compareTo(a.getSentAt());
            return byTime != 0 ? byTime : Long.compare(b.getId(), a.getId());
        });

        Map<Long, ConversationView> byPerson = new LinkedHashMap<>();
        Map<Long, Long> unreadByPerson = new LinkedHashMap<>();

        for (Message message : all) {
            boolean iAmRecipient = message.getRecipient().getId().equals(me.getId());
            AppUser other = iAmRecipient ? message.getSender() : message.getRecipient();
            if (iAmRecipient && !message.isRead()) {
                /*
                 * Counted with an explicit local rather than
                 * merge(id, 1L, Long::sum).
                 *
                 * Long::sum takes two primitive longs, so routing it through
                 * Map.merge's unannotated BiFunction<Long,Long,Long> means
                 * Eclipse's null analysis sees two Longs being unboxed with no
                 * guarantee either is non-null - which is a fair complaint
                 * about the SIGNATURE even though this map never holds a null
                 * value. Writing the absent case out makes the guarantee
                 * something flow analysis verifies natively, with nothing
                 * annotated and nothing suppressed. Same reasoning as
                 * Require.orThrow; see CLAUDE.md's Nullability section.
                 */
                Long soFar = unreadByPerson.get(other.getId());
                unreadByPerson.put(other.getId(), soFar == null ? 1L : soFar + 1L);
            }
            byPerson.computeIfAbsent(other.getId(), id -> new ConversationView(
                    other.getId(),
                    other.getUsername(),
                    other.getRole().name(),
                    fullNameOf(other),
                    summarise(message.getBody()),
                    message.getSentAt(),
                    0L));
        }

        // The unread counts are only complete once every message has been
        // seen, so they are folded in afterwards rather than guessed at while
        // the first message of each conversation is being read.
        return byPerson.values().stream()
                .map(c -> new ConversationView(c.personId(), c.username(), c.role(),
                        c.fullName(), c.lastMessage(), c.lastMessageAt(),
                        unreadByPerson.getOrDefault(c.personId(), 0L)))
                .toList();
    }

    /**
     * Everybody the caller may write to.
     *
     * Built by proposing candidates and filtering them through the SAME
     * predicate that refuses a send - see the class comment on why this is not
     * a separate query.
     */
    public List<CorrespondentView> listCorrespondents() {
        AppUser me = accounts.currentActiveUser();
        List<CorrespondentView> out = new ArrayList<>();
        for (AppUser candidate : candidatesFor(me)) {
            if (candidate.getId().equals(me.getId()) || !mayWriteTo(me, candidate)) {
                continue;
            }
            out.add(new CorrespondentView(
                    candidate.getId(), candidate.getUsername(), candidate.getRole().name(),
                    fullNameOf(candidate), contextFor(me, candidate)));
        }
        return List.copyOf(out);
    }

    // =====================================================================
    // THE RULE
    // =====================================================================

    /** The refusal, with a message naming why. */
    private void requireMayWriteTo(AppUser me, AppUser them) {
        if (!mayWriteTo(me, them)) {
            throw new AccessDeniedException(
                    "You are not allowed to send a message to '" + them.getUsername() + "'. "
                            + explainWhoYouMayWriteTo(me));
        }
    }

    /**
     * THE single definition of who may write to whom. Everything else in this
     * class - the send, the compose list - goes through here.
     */
    private boolean mayWriteTo(AppUser me, AppUser them) {
        if (me.getId().equals(them.getId())) {
            return false;
        }
        // Anybody may write to an administrator. Being able to reach the office
        // is not a privilege that should depend on who teaches you.
        if (them.getRole() == Role.ADMIN) {
            return true;
        }
        return switch (me.getRole()) {
            case ADMIN -> true;
            case TEACHER -> them.getRole() == Role.TEACHER || teaches(me, them);
            // A student may reach the staff who teach them, and nobody else.
            // Student-to-student is refused here rather than merely hidden.
            case STUDENT -> them.getRole() == Role.TEACHER && teaches(them, me);
        };
    }

    /** Whether {@code teacher} teaches {@code student}, through Course and Enrolment. */
    private boolean teaches(AppUser teacher, AppUser student) {
        if (teacher.getRole() != Role.TEACHER || student.getRole() != Role.STUDENT) {
            return false;
        }
        for (Course course : courses.findByTeacherOrderByIdAsc(teacher)) {
            if (enrolments.existsByStudentAndSchoolClass(student, course.getSchoolClass())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The candidates worth testing, per role.
     *
     * Narrowed before filtering purely so an admin does not load every account
     * in the school to answer "who may a student write to". The filter above is
     * still what decides; this only avoids proposing people it would obviously
     * reject.
     */
    private List<AppUser> candidatesFor(AppUser me) {
        List<AppUser> out = new ArrayList<>(users.findByRoleOrderByUsernameAsc(Role.ADMIN));
        switch (me.getRole()) {
            case ADMIN -> {
                out.addAll(users.findByRoleOrderByUsernameAsc(Role.TEACHER));
                out.addAll(users.findByRoleOrderByUsernameAsc(Role.STUDENT));
            }
            case TEACHER -> {
                out.addAll(users.findByRoleOrderByUsernameAsc(Role.TEACHER));
                // Only the students they actually teach - the same derivation
                // SchoolService.listMyStudents makes, from the same two tables.
                for (Course course : courses.findByTeacherOrderByIdAsc(me)) {
                    for (Enrolment enrolment
                            : enrolments.findBySchoolClassOrderByIdAsc(course.getSchoolClass())) {
                        out.add(enrolment.getStudent());
                    }
                }
            }
            // Only the teachers who teach them. findCoursesForStudent is the
            // same query the student's own timetable is built from.
            case STUDENT -> courses.findCoursesForStudent(me)
                    .forEach(course -> out.add(course.getTeacher()));
        }
        // Distinct by id, keeping first appearance: a teacher of two of a
        // student's subjects must appear once, not twice.
        Map<Long, AppUser> unique = new LinkedHashMap<>();
        out.forEach(u -> unique.putIfAbsent(u.getId(), u));
        return List.copyOf(unique.values());
    }

    /** Why this person is reachable, shown beside their name in the compose form. */
    private String contextFor(AppUser me, AppUser them) {
        if (them.getRole() == Role.ADMIN) {
            return "Administration";
        }
        if (me.getRole() == Role.STUDENT) {
            return subjectsTaughtTo(them, me);
        }
        if (me.getRole() == Role.TEACHER && them.getRole() == Role.STUDENT) {
            return classOf(them);
        }
        if (them.getRole() == Role.TEACHER) {
            return "Teacher";
        }
        return classOf(them);
    }

    private String subjectsTaughtTo(AppUser teacher, AppUser student) {
        List<String> subjects = new ArrayList<>();
        for (Course course : courses.findByTeacherOrderByIdAsc(teacher)) {
            if (enrolments.existsByStudentAndSchoolClass(student, course.getSchoolClass())
                    && !subjects.contains(course.getSubject().getName())) {
                subjects.add(course.getSubject().getName());
            }
        }
        return subjects.isEmpty() ? "Teacher" : String.join(", ", subjects);
    }

    private String classOf(AppUser student) {
        List<Enrolment> rows = enrolments.findByStudentOrderByIdAsc(student);
        if (rows.isEmpty()) {
            return "No class";
        }
        SchoolClass schoolClass = rows.get(0).getSchoolClass();
        return schoolClass.getName();
    }

    private String explainWhoYouMayWriteTo(AppUser me) {
        return switch (me.getRole()) {
            case ADMIN -> "";
            case TEACHER -> "Teachers can message their own students, other teachers, "
                    + "and administrators.";
            case STUDENT -> "Students can message the teachers who teach them, "
                    + "and administrators.";
        };
    }

    // =====================================================================
    // Small shared helpers
    // =====================================================================

    /** A one-line preview, for a conversation list and a notification body. */
    private String summarise(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 140 ? flat : flat.substring(0, 139) + "...";
    }

    /**
     * Somebody's real name, falling back to their sign-in id.
     *
     * Written with an explicit local rather than
     * {@code .map(PersonProfile::getFullName)}: Optional is an unannotated JDK
     * type, so passing a method reference whose receiver must be @NonNull
     * through Optional.map's Function leaves Eclipse unable to verify the
     * receiver. Unwrapping first turns it into an ordinary null check that
     * needs no annotation at all.
     */
    private String fullNameOf(AppUser user) {
        PersonProfile profile = profiles.findByAppUser(user).orElse(null);
        return profile == null ? user.getUsername() : profile.getFullName();
    }

    @NonNull
    private AppUser requireAccount(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("A message needs somebody to send it to.");
        }
        return Require.orThrow(users.findById(id),
                () -> new ResourceNotFoundException("No account found with id = " + id));
    }

    @NonNull
    private Message requireMessage(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Message id must not be null.");
        }
        return Require.orThrow(messages.findById(id),
                () -> new ResourceNotFoundException("No message found with id = " + id));
    }
}
