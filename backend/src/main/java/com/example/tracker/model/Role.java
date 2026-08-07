package com.example.tracker.model;

/**
 * What a signed-in person is allowed to do.
 *
 * A STUDENT can track their own work; a TEACHER can set work for others.
 * Scattering role names as strings across the code is how authorisation rules
 * drift apart - if a role-specific decision is needed anywhere, it belongs
 * here and nowhere else.
 *
 * ADMIN was added later (6 August 2026) and deliberately does not slot into
 * any of the composite role-pinned foreign keys that already exist -
 * enrolment, course and assessment each hardcode the specific role they
 * require (STUDENT or TEACHER) via a CHECK constraint plus a two-column
 * foreign key into app_user (id, role). An admin never enrols in a class,
 * teaches a course or is marked, so none of those constraints needed to
 * change; adding ADMIN only widened app_user's own ck_app_user_role CHECK
 * (see V7__add_admin_role_and_audit_log.sql).
 *
 * Stored with EnumType.STRING for the same reason AssignmentStatus is: an
 * ordinal records a position, so reordering these constants would silently
 * re-label every existing account.
 */
public enum Role {

    /** Can see and submit their own assignments. Cannot create work. */
    STUDENT,

    /** Can create assignments, and can see every assignment. */
    TEACHER,

    /**
     * Manages accounts and the teacher-to-student relationship; sees the
     * audit log. Cannot set work, submit work, or record a mark - those stay
     * exactly what they were, a TEACHER/STUDENT relationship, and an admin
     * session is deliberately not a shortcut into either.
     */
    ADMIN
}
