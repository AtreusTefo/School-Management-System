-- V14: the attendance register - who was in which lesson, on which date.
--
-- ATTACHED TO A TIMETABLE SLOT, NOT TO A DAY
-- ------------------------------------------
-- A record here says "this student, in THIS lesson, on this date". The
-- alternative - one row per student per day - is simpler and answers a
-- different, weaker question: it can say somebody was absent on Tuesday, but
-- not which lessons they missed, which is what a subject teacher actually
-- needs and what any later "attendance by subject" report is built from.
--
-- It is only possible because V11 gave lessons an identity (timetable_slot).
-- Recording per-day first and migrating to per-lesson later would have meant a
-- second migration reshaping the table AND a backfill that could not invent
-- the missing information.
--
-- WHAT THE UNIQUE CONSTRAINT MEANS
-- --------------------------------
-- (timetable_slot_id, student_id, lesson_date) - one mark per student per
-- lesson per date. Taking the register twice for the same lesson is a
-- correction, not a second record, and this is what forces it to be one.
--
-- WHY lesson_date IS A COLUMN AND NOT DERIVED
-- The slot says "Mondays, Period 3". It does not say WHICH Monday, and a
-- register is a fact about one specific occurrence. The service checks that
-- the date's weekday actually matches the slot's day and that it falls inside
-- the slot's term - two rules a CHECK constraint cannot express, because both
-- need to read another table.
--
-- THE ROLE PINNING is the same technique as everywhere else in this schema
-- (see V4): the role travels beside the id and a composite foreign key makes
-- the pair agree with app_user, so only a STUDENT can be marked and only a
-- TEACHER or an ADMIN can do the marking.


-- =============================================================================
-- The columns the composite foreign key below points at
-- =============================================================================
-- timetable_slot already has a primary key on id alone; this pair exists so the
-- reference from attendance can carry the class through, which is what lets a
-- register be read back per class without a join.
ALTER TABLE timetable_slot
    ADD CONSTRAINT uq_timetable_slot_id_class UNIQUE (id, class_id);
GO


CREATE TABLE attendance (
    id                BIGINT       IDENTITY(1,1) NOT NULL,

    timetable_slot_id BIGINT       NOT NULL,
    class_id          BIGINT       NOT NULL,   -- pinned to the slot's own class
    lesson_date       DATE         NOT NULL,

    student_id        BIGINT       NOT NULL,
    student_role      NVARCHAR(20) NOT NULL,

    status            NVARCHAR(20) NOT NULL,
    -- Free text, and optional: "at a funeral", "sent home ill". Only ever read
    -- by a person, never parsed.
    note              NVARCHAR(200) NULL,

    recorded_by_id    BIGINT       NOT NULL,
    recorded_by_role  NVARCHAR(20) NOT NULL,
    recorded_at       DATETIMEOFFSET NOT NULL,

    version           BIGINT       NULL,

    CONSTRAINT pk_attendance PRIMARY KEY (id),

    -- One mark per student per lesson per date - see the file header.
    CONSTRAINT uq_attendance_slot_student_date
        UNIQUE (timetable_slot_id, student_id, lesson_date),

    -- EnumType.STRING on the Java side; these are the four constants of
    -- AttendanceStatus, spelled out so the database refuses anything else
    -- whatever wrote it.
    CONSTRAINT ck_attendance_status CHECK (status IN
        ('PRESENT', 'ABSENT', 'LATE', 'EXCUSED')),

    -- Only a student can be marked present.
    CONSTRAINT ck_attendance_student_role CHECK (student_role = 'STUDENT'),
    CONSTRAINT fk_attendance_student
        FOREIGN KEY (student_id, student_role) REFERENCES app_user (id, role),

    -- A register is taken by a teacher, and corrected by a teacher or an
    -- admin. Two values rather than one, so the CHECK is an IN rather than an
    -- equality - and the composite key still pins it to that account's real
    -- role, so neither half can be claimed without the other.
    CONSTRAINT ck_attendance_recorded_by_role CHECK (recorded_by_role IN ('TEACHER', 'ADMIN')),
    CONSTRAINT fk_attendance_recorded_by
        FOREIGN KEY (recorded_by_id, recorded_by_role) REFERENCES app_user (id, role),

    -- The lesson, and the class carried through from it so it cannot disagree.
    CONSTRAINT fk_attendance_slot_class
        FOREIGN KEY (timetable_slot_id, class_id) REFERENCES timetable_slot (id, class_id)
);
GO


-- =============================================================================
-- Indexes - the three ways a register is actually read
-- =============================================================================
-- Written here with the table rather than deferred to a later migration,
-- because unlike V12 (which retrofitted indexes onto tables that already
-- existed) these are known from the first row.

-- "Take/show the register for this lesson on this date" - the screen itself.
-- The UNIQUE constraint above leads with timetable_slot_id and would serve
-- this, but only by scanning every date for that slot; leading with the pair
-- makes it a seek.
CREATE INDEX ix_attendance_slot_date ON attendance (timetable_slot_id, lesson_date);
GO

-- "This student's attendance record" - the pastoral question, and the one a
-- parent asks. Newest first, which is the order it is always read in.
CREATE INDEX ix_attendance_student_date ON attendance (student_id, lesson_date DESC);
GO

-- "Who was absent across the school on this date."
CREATE INDEX ix_attendance_date ON attendance (lesson_date);
GO
