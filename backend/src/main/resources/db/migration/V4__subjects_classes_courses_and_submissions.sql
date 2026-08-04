-- V4: subjects, classes, enrolment, courses, and PDF submissions.
--
-- This is the largest structural change the schema has taken, and it exists
-- because the old model could not express what a school actually is. Until now
-- an assignment belonged to exactly one person through `owner_id`, which made
-- four of the requirements literally unrepresentable:
--
--   - a student taught by more than one teacher
--   - a student taught more than one subject
--   - a teacher setting one piece of work for a whole class
--   - a teacher teaching more than one class or subject
--
-- None of those are features that can be bolted onto a single owner column. They
-- are all statements about RELATIONSHIPS, so they need tables.
--
-- THE CENTRAL SPLIT
-- `assignment` used to mean two different things at once: the work a teacher set,
-- and one student's progress on it. Those have different owners, different
-- lifecycles and different cardinalities - one piece of work, many students - so
-- they are now two tables:
--
--   assignment  the task itself. Set by a teacher, belongs to a course.
--   submission  one student's state for one assignment, with their PDF.
--
-- Collapsing them was what forced "one assignment, one student" in the first
-- place. Separating them is what makes a class-wide assignment possible.
--
-- WHY THE "GO" SEPARATORS - unchanged from V2. SQL Server compiles an entire
-- batch before running any of it, so a statement referring to a column that an
-- earlier statement in the same batch created fails to compile with "Invalid
-- column name". GO ends the batch.


-- =============================================================================
-- 1. The composite key that makes role guards enforceable
-- =============================================================================
-- A foreign key can only point at a UNIQUE or PRIMARY key. Adding (id, role) as
-- a unique pair lets every table below reference a user AND pin the role that
-- user must have, in the same constraint.
--
-- This is what turns "only students can be enrolled" and "only teachers can
-- teach" from application policy into database fact. Without it, both rules
-- would live only in Java, and any script, admin or future service could enrol a
-- teacher in a class - which is exactly the class of mistake this project keeps
-- pushing down into the schema.
--
-- It has a second, deliberate effect: a user's role can no longer be edited
-- while rows depend on it. Promoting a student to teacher requires dealing with
-- their enrolments first. That is not an obstacle, it is the guarantee working -
-- the alternative is a "teacher" who is still enrolled as a pupil.
ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_id_role UNIQUE (id, role);
GO


-- =============================================================================
-- 2. Subjects
-- =============================================================================
CREATE TABLE subject (
    id      BIGINT        IDENTITY(1,1) NOT NULL,
    code    NVARCHAR(20)  NOT NULL,
    name    NVARCHAR(100) NOT NULL,
    version BIGINT        NULL,

    CONSTRAINT pk_subject PRIMARY KEY (id),

    -- Both are unique. The code is what people type; the name is what they read.
    -- Allowing two subjects called "Mathematics" would make every later report
    -- ambiguous in a way no application check can repair after the fact.
    CONSTRAINT uq_subject_code UNIQUE (code),
    CONSTRAINT uq_subject_name UNIQUE (name),

    CONSTRAINT ck_subject_code_not_blank CHECK (LEN(LTRIM(RTRIM(code))) > 0),
    CONSTRAINT ck_subject_name_not_blank CHECK (LEN(LTRIM(RTRIM(name))) > 0)
);
GO


-- =============================================================================
-- 3. Classes
-- =============================================================================
-- Named school_class, not class. `class` is a reserved word in SQL and a keyword
-- in Java, and fighting it with quoted identifiers on every single query is a
-- poor trade for a six-character saving. Same reasoning as AppUser over User.
CREATE TABLE school_class (
    id      BIGINT       IDENTITY(1,1) NOT NULL,
    name    NVARCHAR(50) NOT NULL,
    version BIGINT       NULL,

    CONSTRAINT pk_school_class PRIMARY KEY (id),
    CONSTRAINT uq_school_class_name UNIQUE (name),
    CONSTRAINT ck_school_class_name_not_blank CHECK (LEN(LTRIM(RTRIM(name))) > 0)
);
GO


-- =============================================================================
-- 4. Enrolment - which students are in which class
-- =============================================================================
-- Many-to-many, so a student can be in more than one class and a class holds
-- many students.
CREATE TABLE enrolment (
    id           BIGINT       IDENTITY(1,1) NOT NULL,
    student_id   BIGINT       NOT NULL,
    -- Carried so the composite foreign key below can pin it. It duplicates
    -- app_user.role deliberately: the duplication is what the FK constrains, so
    -- the two can never disagree. A denormalisation that is enforced is not the
    -- same thing as a denormalisation that is hoped for.
    student_role NVARCHAR(20) NOT NULL,
    class_id     BIGINT       NOT NULL,
    version      BIGINT       NULL,

    CONSTRAINT pk_enrolment PRIMARY KEY (id),

    -- The same student cannot be enrolled in the same class twice. Without this,
    -- a double-clicked form produces two rows and every later count is wrong.
    CONSTRAINT uq_enrolment_student_class UNIQUE (student_id, class_id),

    CONSTRAINT ck_enrolment_student_role CHECK (student_role = 'STUDENT'),

    -- Only a STUDENT can be enrolled - enforced by the database, not by Java.
    CONSTRAINT fk_enrolment_student
        FOREIGN KEY (student_id, student_role) REFERENCES app_user (id, role),

    CONSTRAINT fk_enrolment_class
        FOREIGN KEY (class_id) REFERENCES school_class (id)
);
GO

CREATE INDEX ix_enrolment_class ON enrolment (class_id);
GO


-- =============================================================================
-- 5. Course - one subject, taught to one class, by one teacher
-- =============================================================================
-- This single table carries four of the requirements at once, because they are
-- all the same statement seen from different ends:
--
--   a teacher teaches many subjects      many rows, same teacher_id
--   a teacher teaches many classes       many rows, same teacher_id
--   a student is taught many subjects    via their class's courses
--   a student has many teachers          via their class's courses
--
-- Nothing else needs to be added for any of them. That is the sign the model is
-- right: the requirements fall out of the relationship rather than each needing
-- their own special case.
CREATE TABLE course (
    id           BIGINT       IDENTITY(1,1) NOT NULL,
    subject_id   BIGINT       NOT NULL,
    class_id     BIGINT       NOT NULL,
    teacher_id   BIGINT       NOT NULL,
    teacher_role NVARCHAR(20) NOT NULL,
    version      BIGINT       NULL,

    CONSTRAINT pk_course PRIMARY KEY (id),

    -- One row per teacher/subject/class combination. Co-teaching is allowed -
    -- two teachers may share a class for a subject - but the identical row twice
    -- is not, because that would double every assignment fan-out below.
    CONSTRAINT uq_course_subject_class_teacher
        UNIQUE (subject_id, class_id, teacher_id),

    CONSTRAINT ck_course_teacher_role CHECK (teacher_role = 'TEACHER'),

    CONSTRAINT fk_course_subject FOREIGN KEY (subject_id) REFERENCES subject (id),
    CONSTRAINT fk_course_class   FOREIGN KEY (class_id)   REFERENCES school_class (id),

    -- Only a TEACHER can teach. Same mechanism as enrolment, opposite role.
    CONSTRAINT fk_course_teacher
        FOREIGN KEY (teacher_id, teacher_role) REFERENCES app_user (id, role)
);
GO

CREATE INDEX ix_course_teacher ON course (teacher_id);
GO
CREATE INDEX ix_course_class   ON course (class_id);
GO


-- =============================================================================
-- 6. Legacy holding records, so no existing row is destroyed
-- =============================================================================
-- Existing assignments predate subjects, classes and courses, and every one of
-- them needs a course to point at. Deleting them would be easier and would throw
-- away real data; parking them in an explicitly-named holding course keeps them
-- and makes their provenance obvious to whoever finds them later.
INSERT INTO subject (code, name, version) VALUES ('GEN', 'General', 0);
GO
INSERT INTO school_class (name, version) VALUES ('Unassigned', 0);
GO

INSERT INTO course (subject_id, class_id, teacher_id, teacher_role, version)
SELECT s.id, c.id, u.id, 'TEACHER', 0
FROM subject s
CROSS JOIN school_class c
CROSS JOIN app_user u
WHERE s.code = 'GEN' AND c.name = 'Unassigned' AND u.username = 'teacher';
GO


-- =============================================================================
-- 7. assignment gains its course, and loses its single owner
-- =============================================================================
-- Same four-step shape as V2: add nullable, backfill, tighten, then constrain.
-- A column cannot be born NOT NULL on a populated table.
ALTER TABLE assignment ADD course_id BIGINT NULL;
GO

UPDATE assignment
SET course_id = (
    SELECT TOP 1 co.id
    FROM course co
    JOIN subject s      ON s.id = co.subject_id
    JOIN school_class c ON c.id = co.class_id
    WHERE s.code = 'GEN' AND c.name = 'Unassigned')
WHERE course_id IS NULL;
GO

ALTER TABLE assignment ALTER COLUMN course_id BIGINT NOT NULL;
GO

ALTER TABLE assignment
    ADD CONSTRAINT fk_assignment_course
    FOREIGN KEY (course_id) REFERENCES course (id);
GO

CREATE INDEX ix_assignment_course ON assignment (course_id);
GO


-- =============================================================================
-- 8. Submission - one student's state for one assignment
-- =============================================================================
CREATE TABLE submission (
    id            BIGINT       IDENTITY(1,1) NOT NULL,
    assignment_id BIGINT       NOT NULL,
    student_id    BIGINT       NOT NULL,
    student_role  NVARCHAR(20) NOT NULL,
    status        NVARCHAR(20) NOT NULL,
    submitted_at  DATETIME2    NULL,
    version       BIGINT       NULL,

    CONSTRAINT pk_submission PRIMARY KEY (id),

    -- THE RULE THAT MAKES THE WHOLE FAN-OUT SAFE.
    -- One student has exactly one submission per assignment. Everything else -
    -- the transaction, the version column, the "already submitted" check - is
    -- defence in depth behind this line. If a fan-out is somehow run twice, this
    -- is what refuses the duplicate rather than quietly doubling the class list.
    CONSTRAINT uq_submission_assignment_student UNIQUE (assignment_id, student_id),

    CONSTRAINT ck_submission_status CHECK (status IN ('IN_PROGRESS', 'SUBMITTED')),
    CONSTRAINT ck_submission_student_role CHECK (student_role = 'STUDENT'),

    -- INTRA-ROW CONSISTENCY: the status and the timestamp cannot contradict each
    -- other. A row claiming SUBMITTED with no submission time, or IN_PROGRESS
    -- with one, is not a valid state of the world - so the database refuses to
    -- hold it, rather than leaving every reader to guess which field to believe.
    CONSTRAINT ck_submission_status_time CHECK (
           (status = 'SUBMITTED'   AND submitted_at IS NOT NULL)
        OR (status = 'IN_PROGRESS' AND submitted_at IS NULL)),

    -- No ON DELETE CASCADE, consistent with the decision taken in V2. Deleting
    -- an assignment that students have already worked on must be an explicit act
    -- in the service, which can refuse it, not a side effect the schema performs
    -- silently.
    CONSTRAINT fk_submission_assignment
        FOREIGN KEY (assignment_id) REFERENCES assignment (id),

    CONSTRAINT fk_submission_student
        FOREIGN KEY (student_id, student_role) REFERENCES app_user (id, role)
);
GO

CREATE INDEX ix_submission_student ON submission (student_id);
GO
CREATE INDEX ix_submission_assignment ON submission (assignment_id);
GO


-- =============================================================================
-- 9. The uploaded PDF
-- =============================================================================
-- STORED IN THE DATABASE, NOT ON DISK - and that is a consistency decision, not
-- a storage preference.
--
-- A file on disk with a row pointing at it is two writes that can only be made
-- atomic with real effort. Every failure mode produces garbage: a row with no
-- file, a file with no row, a rolled-back transaction leaving the bytes behind.
-- Inside the database the upload is part of the same transaction as everything
-- else, so it commits or vanishes with it, and a backup captures the work rather
-- than a set of dangling references.
--
-- The cost is honest: large binaries make the database bigger and backups
-- slower. At ten megabytes a file for a school assignment tracker that is a
-- trade worth making. At video scale it would not be, and the answer then is
-- object storage with a deliberate reconciliation job - not a silent copy.
CREATE TABLE submission_file (
    id            BIGINT         IDENTITY(1,1) NOT NULL,
    submission_id BIGINT         NOT NULL,
    filename      NVARCHAR(255)  NOT NULL,
    content_type  NVARCHAR(100)  NOT NULL,
    size_bytes    BIGINT         NOT NULL,
    -- SHA-256 of the bytes, as 64 lowercase hex characters. Stored so corruption
    -- is DETECTABLE: without a checksum, a truncated or altered upload looks
    -- exactly like a valid one and the first person to find out is the teacher
    -- who cannot open it.
    sha256        NVARCHAR(64)   NOT NULL,
    content       VARBINARY(MAX) NOT NULL,
    uploaded_at   DATETIME2      NOT NULL,
    version       BIGINT         NULL,

    CONSTRAINT pk_submission_file PRIMARY KEY (id),

    -- One current file per submission. Re-uploading REPLACES, which is what a
    -- student means by "I picked the wrong file"; keeping every attempt would
    -- make "which one is being marked?" ambiguous at exactly the wrong moment.
    CONSTRAINT uq_submission_file_submission UNIQUE (submission_id),

    -- PDF ONLY, at the storage layer. The service also checks the file's leading
    -- bytes, because a declared content type is client-supplied and trivially
    -- spoofed - but this constraint means even a direct INSERT cannot register a
    -- .exe as coursework.
    CONSTRAINT ck_submission_file_pdf CHECK (content_type = 'application/pdf'),

    -- Empty files are not uploads; ten megabytes is the ceiling.
    CONSTRAINT ck_submission_file_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),

    CONSTRAINT ck_submission_file_sha256 CHECK (LEN(sha256) = 64),
    CONSTRAINT ck_submission_file_name CHECK (LEN(LTRIM(RTRIM(filename))) > 0),

    CONSTRAINT fk_submission_file_submission
        FOREIGN KEY (submission_id) REFERENCES submission (id)
);
GO


-- =============================================================================
-- 10. Migrate existing ownership into submissions
-- =============================================================================
-- Every existing assignment owned by a STUDENT becomes that student's
-- submission, carrying its status across so nothing handed in is forgotten.
--
-- Teacher-owned assignments get no submission, and that is correct rather than a
-- gap: under the new model an assignment is the work a teacher SET, and a
-- teacher does not hand work in to themselves. Their status is the one piece of
-- information this migration deliberately drops, because it no longer describes
-- anything real.

-- Those students must be in the holding class for the data to be coherent.
INSERT INTO enrolment (student_id, student_role, class_id, version)
SELECT DISTINCT a.owner_id, 'STUDENT', c.id, 0
FROM assignment a
JOIN app_user u      ON u.id = a.owner_id AND u.role = 'STUDENT'
CROSS JOIN school_class c
WHERE c.name = 'Unassigned';
GO

INSERT INTO submission (assignment_id, student_id, student_role, status, submitted_at, version)
SELECT a.id,
       a.owner_id,
       'STUDENT',
       a.status,
       -- The old table never recorded WHEN something was submitted, so there is
       -- no true value to carry. The CHECK above requires one for a SUBMITTED
       -- row, so migrated rows are stamped with the migration time. Inventing a
       -- plausible-looking past date would be worse: it would read as evidence.
       CASE WHEN a.status = 'SUBMITTED' THEN SYSUTCDATETIME() ELSE NULL END,
       0
FROM assignment a
JOIN app_user u ON u.id = a.owner_id AND u.role = 'STUDENT';
GO


-- =============================================================================
-- 11. Retire the columns the split replaced
-- =============================================================================
-- Constraints and indexes must go before the columns they depend on.
ALTER TABLE assignment DROP CONSTRAINT fk_assignment_owner;
GO
DROP INDEX ix_assignment_owner ON assignment;
GO
DROP INDEX ix_assignment_status ON assignment;
GO
ALTER TABLE assignment DROP CONSTRAINT ck_assignment_status;
GO
ALTER TABLE assignment DROP COLUMN owner_id;
GO
ALTER TABLE assignment DROP COLUMN status;
GO


-- =============================================================================
-- 12. Assignment fields the new model needs
-- =============================================================================
-- Instructions for the work. Nullable: a title-only assignment is legitimate,
-- and forcing a description would produce a column full of ".".
ALTER TABLE assignment ADD description NVARCHAR(2000) NULL;
GO

-- Who set it. Denormalised from course.teacher_id on purpose: a course may be
-- co-taught, and "which teacher actually set this" is a different question from
-- "who teaches this course". Constrained to a real teacher by the same composite
-- key mechanism used everywhere else in this migration.
ALTER TABLE assignment ADD created_by_id BIGINT NULL;
GO
ALTER TABLE assignment ADD created_by_role NVARCHAR(20) NULL;
GO

UPDATE assignment
SET created_by_id = (SELECT id FROM app_user WHERE username = 'teacher'),
    created_by_role = 'TEACHER'
WHERE created_by_id IS NULL;
GO

ALTER TABLE assignment ALTER COLUMN created_by_id BIGINT NOT NULL;
GO
ALTER TABLE assignment ALTER COLUMN created_by_role NVARCHAR(20) NOT NULL;
GO

ALTER TABLE assignment
    ADD CONSTRAINT ck_assignment_created_by_role CHECK (created_by_role = 'TEACHER');
GO

ALTER TABLE assignment
    ADD CONSTRAINT fk_assignment_created_by
    FOREIGN KEY (created_by_id, created_by_role) REFERENCES app_user (id, role);
GO
