package com.example.tracker.model;

/**
 * The set of states an assignment may be in.
 *
 * WHY AN ENUM INSTEAD OF A STRING?
 * --------------------------------
 * Status used to be a plain String. That meant the type system allowed —
 * and the database happily stored — values that mean nothing:
 *
 *     assignment.setStatus("banana");   // compiled, saved, no complaint
 *
 * An enum makes those values impossible to write in the first place, and
 * Hibernate additionally emits a CHECK constraint on the column, so the
 * database rejects anything outside this list even if a row is inserted by
 * hand or by another application entirely.
 *
 * The names below are the values sent over the wire: Jackson serialises an
 * enum to its name, so the JSON stays exactly as it was ("IN_PROGRESS",
 * "SUBMITTED") and no client has to change.
 */
public enum AssignmentStatus {

    /** The starting state of every new assignment. Not yet handed in. */
    IN_PROGRESS,

    /** Handed in. Terminal — nothing moves out of this state today. */
    SUBMITTED
}
