package com.example.tracker.model;

/**
 * What a signed-in person is allowed to do.
 *
 * Two roles is deliberately the fewest that makes the distinction real. A
 * STUDENT can track their own work; a TEACHER can set work for others. If a
 * third role ever appears, it belongs here and nowhere else - scattering role
 * names as strings across the code is how authorisation rules drift apart.
 *
 * Stored with EnumType.STRING for the same reason AssignmentStatus is: an
 * ordinal records a position, so reordering these constants would silently
 * re-label every existing account.
 */
public enum Role {

    /** Can see and submit their own assignments. Cannot create work. */
    STUDENT,

    /** Can create assignments, and can see every assignment. */
    TEACHER
}
