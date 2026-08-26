-- V11: terms, periods, and a timetable that says WHEN a course is taught.
--
-- WHAT WAS MISSING, AND WHY IT MATTERS NOW
-- ----------------------------------------
-- Until this migration "timetable" in this system meant which teacher teaches
-- which subject to which class - the `course` table - and nothing about time.
-- That was a deliberate limit, stated plainly in the documentation rather than
-- implied. It is being lifted on purpose, so the wording goes with it.
--
-- Three tables, each answering one question:
--
--   term            over what stretch of the year? (Term 1, 12 Jan - 27 Mar)
--   timetable_period which slot of the school day?  (Period 3, 10:15 - 11:00)
--   timetable_slot  which course, on which weekday, in which period, in which term?
--
-- WHY class_id AND teacher_id ARE REPEATED ON timetable_slot
-- ----------------------------------------------------------
-- Both are already reachable through course_id, so storing them again looks
-- like duplication - and it would be, if they were free to disagree. They are
-- not: each is pinned by a COMPOSITE foreign key back to the course row it
-- came from, so the only value either column can legally hold is that course's
-- own. This is the same technique V4 already uses to pin a role, applied to a
-- different fact.
--
-- What it buys is the entire point of the table. A timetable's two real rules
-- are that a CLASS cannot be in two lessons at once, and a TEACHER cannot be
-- in two places at once. Expressed against course_id alone, neither is a
-- constraint the database can check - it would have to follow the reference to
-- find out who is involved. With the two columns present and pinned, both
-- collapse into ordinary UNIQUE constraints, and a double-booking becomes
-- impossible to insert rather than merely rejected by whichever service
-- remembered to look. A clash check written only in Java holds right up until
-- the first import script.
--
-- WHY GO SEPARATORS - unchanged from V2/V8. SQL Server compiles a whole batch
-- before running any of it, so a statement using something an earlier statement
-- in the same batch created fails to compile.


-- =============================================================================
-- 1. Terms - a named stretch of the school year
-- =============================================================================
CREATE TABLE term (
    id         BIGINT       IDENTITY(1,1) NOT NULL,
    name       NVARCHAR(60) NOT NULL,
    start_date DATE         NOT NULL,
    end_date   DATE         NOT NULL,
    version    BIGINT       NULL,

    CONSTRAINT pk_term PRIMARY KEY (id),
    CONSTRAINT uq_term_name UNIQUE (name),
    CONSTRAINT ck_term_name_not_blank CHECK (LTRIM(RTRIM(name)) <> ''),

    -- A term that ends before it starts is not a short term, it is a corrupt
    -- row - the same reasoning ck_assessment_score_within_max already applies.
    CONSTRAINT ck_term_dates CHECK (end_date >= start_date)
);
GO

-- =============================================================================
-- 2. Periods - a numbered slot in the school day
-- =============================================================================
-- NAMED timetable_period, NOT period. PERIOD is a keyword in SQL Server's
-- temporal-table syntax (PERIOD FOR SYSTEM_TIME), and while a table of that
-- name can be quoted into working, every ad-hoc query against it then has to
-- remember to. The prefix costs nothing and removes the trap.
CREATE TABLE timetable_period (
    id         BIGINT       IDENTITY(1,1) NOT NULL,
    name       NVARCHAR(40) NOT NULL,
    -- The order periods run in. Separate from the clock time because break and
    -- registration periods are ordered too, and sorting by start_time alone
    -- would be at the mercy of two periods sharing a start.
    sequence_no INT         NOT NULL,
    start_time TIME(0)      NOT NULL,
    end_time   TIME(0)      NOT NULL,
    version    BIGINT       NULL,

    CONSTRAINT pk_timetable_period PRIMARY KEY (id),
    CONSTRAINT uq_timetable_period_name UNIQUE (name),
    CONSTRAINT uq_timetable_period_sequence UNIQUE (sequence_no),
    CONSTRAINT ck_timetable_period_name_not_blank CHECK (LTRIM(RTRIM(name)) <> ''),
    CONSTRAINT ck_timetable_period_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_timetable_period_times CHECK (end_time > start_time)
);
GO

-- =============================================================================
-- 3. The columns the composite foreign keys below point AT
-- =============================================================================
-- SQL Server will only accept a foreign key that references a set of columns
-- carrying a UNIQUE (or primary key) constraint. course.id is already unique on
-- its own, which makes each of these pairs trivially unique too - they exist so
-- the references in section 4 are legal, exactly as V4 added
-- uq_app_user_id_role for the role pinning.
ALTER TABLE course
    ADD CONSTRAINT uq_course_id_class UNIQUE (id, class_id);
GO

ALTER TABLE course
    ADD CONSTRAINT uq_course_id_teacher UNIQUE (id, teacher_id);
GO

-- =============================================================================
-- 4. The timetable itself
-- =============================================================================
CREATE TABLE timetable_slot (
    id          BIGINT       IDENTITY(1,1) NOT NULL,
    term_id     BIGINT       NOT NULL,
    period_id   BIGINT       NOT NULL,
    course_id   BIGINT       NOT NULL,
    day_of_week NVARCHAR(9)  NOT NULL,

    -- Pinned copies of the course's own class and teacher - see the file
    -- header. Never written independently; AdminService/SchoolService read them
    -- off the course row being scheduled.
    class_id    BIGINT       NOT NULL,
    teacher_id  BIGINT       NOT NULL,

    version     BIGINT       NULL,

    CONSTRAINT pk_timetable_slot PRIMARY KEY (id),

    -- The names java.time.DayOfWeek produces, so the enum and the column agree
    -- without a translation table in between. Saturday and Sunday are permitted
    -- deliberately: weekend classes exist, and a constraint that forbade them
    -- would be a policy decision smuggled into the schema.
    CONSTRAINT ck_timetable_slot_day CHECK (day_of_week IN
        ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),

    -- THE TWO RULES THAT MAKE THIS A TIMETABLE RATHER THAN A LIST.
    CONSTRAINT uq_timetable_slot_class
        UNIQUE (term_id, day_of_week, period_id, class_id),
    CONSTRAINT uq_timetable_slot_teacher
        UNIQUE (term_id, day_of_week, period_id, teacher_id),

    CONSTRAINT fk_timetable_slot_term
        FOREIGN KEY (term_id) REFERENCES term (id),
    CONSTRAINT fk_timetable_slot_period
        FOREIGN KEY (period_id) REFERENCES timetable_period (id),

    -- course_id is covered by both composite keys below, so it needs no
    -- single-column foreign key of its own - either one would refuse a
    -- course_id that does not exist.
    CONSTRAINT fk_timetable_slot_course_class
        FOREIGN KEY (course_id, class_id) REFERENCES course (id, class_id),
    CONSTRAINT fk_timetable_slot_course_teacher
        FOREIGN KEY (course_id, teacher_id) REFERENCES course (id, teacher_id)
);
GO

-- Reading a timetable is always "everything for this term", so that is the
-- index. The two UNIQUE constraints above already cover the clash lookups.
CREATE INDEX ix_timetable_slot_term ON timetable_slot (term_id);
GO
