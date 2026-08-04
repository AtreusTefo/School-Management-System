-- V5: correct the timestamp columns V4 declared with the wrong type.
--
-- WHY THIS MIGRATION EXISTS, AND WHY IT IS NOT FOLDED INTO V4
-- ----------------------------------------------------------
-- V4 declared submitted_at and uploaded_at as DATETIME2. The entities map them
-- from java.time.Instant, and Hibernate maps Instant to DATETIMEOFFSET on SQL
-- Server - a different type, because an Instant is a moment with a known offset
-- from UTC while DATETIME2 is a wall-clock reading with no offset at all.
--
-- Startup refused to proceed:
--
--   Schema-validation: wrong column type encountered in column [submitted_at]
--   in table [submission]; found [datetime2 (Types#TIMESTAMP)],
--   but expecting [datetimeoffset(6) (Types#TIMESTAMP_UTC)]
--
-- That message is ddl-auto=validate earning its place. The mismatch would
-- otherwise have surfaced as times that silently lost their offset - the kind of
-- defect that is invisible until somebody in another time zone disputes whether
-- work was handed in before midnight.
--
-- V4 IS LEFT ALONE ON PURPOSE. It has already been applied and recorded, and
-- Flyway stores a checksum of every migration precisely so that an applied one
-- cannot be edited underneath a database that has already run it. Rewriting V4
-- would break every environment that had applied it and would make the history
-- claim something that never happened. A correction gets its own version; that
-- is what versioned migrations are for.

-- 1. The CHECK constraint reads submitted_at, so SQL Server will not allow the
--    column to be altered while it depends on it. Drop, alter, recreate.
ALTER TABLE submission DROP CONSTRAINT ck_submission_status_time;
GO

ALTER TABLE submission ALTER COLUMN submitted_at DATETIMEOFFSET(6) NULL;
GO

ALTER TABLE submission
    ADD CONSTRAINT ck_submission_status_time CHECK (
           (status = 'SUBMITTED'   AND submitted_at IS NOT NULL)
        OR (status = 'IN_PROGRESS' AND submitted_at IS NULL));
GO

-- 2. The upload time has the same origin and the same fix. No constraint reads
--    it, so it can be altered directly.
ALTER TABLE submission_file ALTER COLUMN uploaded_at DATETIMEOFFSET(6) NOT NULL;
GO
