-- V7: the ADMIN role, and an immutable audit log of every Create/Update/Delete.
--
-- WHAT THIS ADDS
-- --------------
-- 1. app_user.role may now be 'ADMIN' as well as 'STUDENT'/'TEACHER'. Nothing
--    else about app_user changes - an admin is still one row in the same
--    table, with the same username/password_hash/must_change_password shape
--    every other account has.
--
-- 2. A new audit_log table, written to on every Create/Update/Delete the
--    application performs (see AuditLogService). Two things distinguish it
--    from every other table in this schema:
--
--    IT IS APPEND-ONLY. No service method updates or deletes a row here -
--    there is deliberately no UPDATE or DELETE endpoint for it at all. A log
--    that could be edited after the fact would not be an audit log.
--
--    IT DOES NOT FOREIGN-KEY TO app_user. Every other reference in this
--    schema is a live foreign key on purpose - see Course, Enrolment,
--    Assessment. This one is the deliberate exception, for a reason specific
--    to what an audit log is FOR: it must still read correctly after the
--    account that produced an entry is gone. If performed_by_id were a real
--    foreign key - even one declared ON DELETE NO_ACTION, this project's
--    usual choice - deleting ANY teacher or admin who had ever performed a
--    single logged action would be refused, because their own audit rows
--    would still reference them. Since virtually every write is logged, that
--    would make every account permanently undeletable the moment it did
--    anything - directly defeating the "delete a teacher" requirement this
--    same EPIC asks for. So performed_by_username and performed_by_role are
--    captured as a plain snapshot at the moment of the action - not derived,
--    not kept in sync, simply true of that moment and frozen - and
--    performed_by_id is stored unconstrained, best-effort, for the ordinary
--    case where the account still exists and a caller wants to join to it.
--
-- WHY THE "GO" SEPARATORS - unchanged from V2. SQL Server compiles an entire
-- batch before running any of it, so a statement referring to something an
-- earlier statement in the same batch created fails to compile.


-- =============================================================================
-- 1. Widen the role check to admit ADMIN
-- =============================================================================
ALTER TABLE app_user DROP CONSTRAINT ck_app_user_role;
GO

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_role CHECK (role IN ('STUDENT', 'TEACHER', 'ADMIN'));
GO


-- =============================================================================
-- 2. The audit log
-- =============================================================================
CREATE TABLE audit_log (
    id                    BIGINT            IDENTITY(1,1) NOT NULL,

    -- What kind of thing changed, e.g. "Assignment", "Course", "AppUser" - the
    -- simple class name, not a table name, since one entity can span more than
    -- one table and the log describes the DOMAIN concept a person changed.
    entity_name           NVARCHAR(60)      NOT NULL,

    -- The id of the row that changed. Nullable: a small number of logged
    -- actions - signing in as an example, if that is ever logged - describe an
    -- event rather than a single row.
    entity_id             BIGINT            NULL,

    action                NVARCHAR(20)      NOT NULL,

    -- A snapshot, not a live reference. See the file header for why this is
    -- deliberately NOT a foreign key.
    performed_by_id       BIGINT            NULL,
    performed_by_username NVARCHAR(50)      NOT NULL,
    performed_by_role     NVARCHAR(20)      NOT NULL,

    performed_at          DATETIMEOFFSET(6) NOT NULL,

    -- A short, human-readable sentence - "Recorded a mark of 34/50 for
    -- 'student' in Mathematics - Grade 10A" - never a raw diff or the request
    -- body. Free text bounded the same way every other free-text column in
    -- this schema is bounded, so one long value cannot silently truncate.
    summary               NVARCHAR(500)     NOT NULL,

    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT ck_audit_log_summary_not_blank CHECK (LEN(LTRIM(RTRIM(summary))) > 0)
);
GO

-- The audit log page reads newest-first and commonly filters by entity or by
-- action; none of the three is free without an index once this table has any
-- real volume in it.
CREATE INDEX ix_audit_log_performed_at ON audit_log (performed_at DESC);
GO
CREATE INDEX ix_audit_log_entity ON audit_log (entity_name, entity_id);
GO
CREATE INDEX ix_audit_log_action ON audit_log (action);
GO
