package com.example.tracker.model;

/**
 * What kind of change an audit log entry records.
 *
 * Three values, matching exactly the three kinds of write this application
 * ever performs on a business entity: something new was made, something
 * existing was changed, or something was removed. Stored with
 * EnumType.STRING for the same reason every other enum in this codebase is -
 * see Role.
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE
}
