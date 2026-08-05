-- V6: assessments - the marks a teacher records for a student.
--
-- WHAT THIS ADDS, AND WHAT IT DELIBERATELY DOES NOT
-- -------------------------------------------------
-- One table. An assessment is a NAMED, SCORED piece of work for one student in
-- one course: "Term 1 Test, 34 out of 50". It can optionally point at the
-- submission it marks, which is how a downloaded PDF and the mark for it stay
-- connected.
--
-- What is NOT here is any stored total, average, percentage or grade. Every one
-- of those is DERIVED on read, for the same reason `overdue` is: a stored
-- average is wrong the moment a single mark is corrected, and keeping it honest
-- needs either a trigger or a job that somebody will forget to run. Computing it
-- from the marks is always right, and the marks are the only thing anybody
-- actually enters.
--
-- THE RULE THIS TABLE EXISTS TO ENFORCE
-- A score cannot exceed the maximum it was marked out of. That is not a
-- formatting preference - "34 out of 20" is not a high mark, it is a corrupt
-- row, and every average computed from it afterwards is silently wrong. So it is
-- a CHECK constraint rather than a validation message.
--
-- WHY THE "GO" SEPARATORS - unchanged from V2. SQL Server compiles an entire
-- batch before running any of it, so a statement referring to a column an
-- earlier statement in the same batch created fails to compile.


-- =============================================================================
-- 1. Let a mark be tied to the submission it marks, safely
-- =============================================================================
-- The composite-key technique used throughout V4, applied to a new question:
-- "is this mark attached to the right student's work?"
--
-- Adding UNIQUE (id, student_id) to submission gives the assessment table
-- something to point a two-column foreign key at. The result is that a mark
-- naming submission 7 and student 3 is only storable if submission 7 really does
-- belong to student 3. Attaching a mark to another student's work becomes
-- impossible rather than merely discouraged.
ALTER TABLE submission
    ADD CONSTRAINT uq_submission_id_student UNIQUE (id, student_id);
GO


-- =============================================================================
-- 2. The assessment table
-- =============================================================================
CREATE TABLE assessment (
    id               BIGINT         IDENTITY(1,1) NOT NULL,

    student_id       BIGINT         NOT NULL,
    student_role     NVARCHAR(20)   NOT NULL,

    -- Which subject, class and teacher this mark belongs to. Scoping a student's
    -- report and a teacher's mark book both fall out of this column.
    course_id        BIGINT         NOT NULL,

    -- Optional: the handed-in work this mark is for. NULL for a test or an exam
    -- that was never uploaded, which is an ordinary case rather than missing data.
    submission_id    BIGINT         NULL,

    -- "Term 1 Test", "Practical 2". Free text on purpose: a school marks things
    -- this system has no name for, and an enum here would need a migration every
    -- time somebody invented an assessment type.
    name             NVARCHAR(100)  NOT NULL,

    -- DECIMAL, not FLOAT. Marks are exact quantities that get added up and
    -- compared; binary floating point cannot represent 0.1 exactly, so totals
    -- drift and two equal marks can compare unequal. Money and marks have the
    -- same requirement for the same reason.
    score            DECIMAL(6,2)   NOT NULL,
    max_score        DECIMAL(6,2)   NOT NULL,

    recorded_by_id   BIGINT         NOT NULL,
    recorded_by_role NVARCHAR(20)   NOT NULL,
    recorded_at      DATETIMEOFFSET(6) NOT NULL,

    version          BIGINT         NULL,

    CONSTRAINT pk_assessment PRIMARY KEY (id),

    -- One mark per named assessment per student per course. Without this, a
    -- double-clicked form records "Term 1 Test" twice and every average computed
    -- afterwards is quietly wrong - and nothing looks broken.
    CONSTRAINT uq_assessment_student_course_name
        UNIQUE (student_id, course_id, name),

    CONSTRAINT ck_assessment_name_not_blank CHECK (LEN(LTRIM(RTRIM(name))) > 0),

    -- THE RULE. A mark cannot exceed what it was marked out of.
    CONSTRAINT ck_assessment_score_within_max CHECK (score <= max_score),

    -- A negative mark is not a mark, and a maximum of zero would make every
    -- percentage a division by zero.
    CONSTRAINT ck_assessment_score_not_negative CHECK (score >= 0),
    CONSTRAINT ck_assessment_max_positive CHECK (max_score > 0),

    CONSTRAINT ck_assessment_student_role CHECK (student_role = 'STUDENT'),
    CONSTRAINT ck_assessment_recorded_by_role CHECK (recorded_by_role = 'TEACHER'),

    -- Only a STUDENT can be marked, and only a TEACHER can mark. Enforced by the
    -- database through the composite key, not by the service being careful.
    CONSTRAINT fk_assessment_student
        FOREIGN KEY (student_id, student_role) REFERENCES app_user (id, role),

    CONSTRAINT fk_assessment_recorded_by
        FOREIGN KEY (recorded_by_id, recorded_by_role) REFERENCES app_user (id, role),

    CONSTRAINT fk_assessment_course
        FOREIGN KEY (course_id) REFERENCES course (id),

    -- The mark-ownership guard described in step 1. When submission_id is NULL
    -- the constraint does not apply, which is exactly right: a test that was
    -- never uploaded has no submission to belong to.
    CONSTRAINT fk_assessment_submission
        FOREIGN KEY (submission_id, student_id) REFERENCES submission (id, student_id)
);
GO

-- The two queries this table exists to serve: a student's own report, and a
-- teacher's mark book for a course.
CREATE INDEX ix_assessment_student ON assessment (student_id);
GO
CREATE INDEX ix_assessment_course ON assessment (course_id);
GO

-- One mark per submission - but only where there IS a submission.
--
-- A plain UNIQUE constraint would not do here. SQL Server treats NULLs as equal
-- for uniqueness, so it would permit exactly ONE assessment with no submission
-- in the entire table and refuse every test and exam after the first. A filtered
-- index applies the rule only to the rows it is about.
CREATE UNIQUE INDEX uq_assessment_submission
    ON assessment (submission_id)
    WHERE submission_id IS NOT NULL;
GO
