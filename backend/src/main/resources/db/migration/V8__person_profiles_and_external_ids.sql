-- V8: person profiles, and an admin-issued external id for every account.
--
-- WHAT THIS ADDS
-- --------------
-- 1. app_user.external_id - the id an admin-created account actually signs
--    in with: STU20260001 for a student, TCH20260001 for a teacher,
--    ADM20260001 for an admin. Nullable, because the accounts seeded for
--    development (teacher, student, admin) predate this rule and keep their
--    literal usernames as a documented dev-convenience exception - see
--    CLAUDE.md. Every account created through the admin panel from here on
--    gets one, and it becomes that account's app_user.username: this schema
--    does not carry the id in a second column, because a login credential
--    that could disagree with the id printed on someone's enrolment letter
--    is worse than one column doing both jobs.
--
-- 2. Three sequences, one per role that gets an auto-generated id. A
--    SEQUENCE rather than "SELECT MAX(...) + 1" because the latter is a
--    genuine race under concurrent admin sessions - two admins creating a
--    student at the same moment could both read the same MAX and both try to
--    insert the same id. A sequence is atomic by construction; nothing here
--    needs a retry loop to stay correct.
--
-- 3. person_profile - the biographical detail an admin's student/teacher
--    forms collect, kept OUT of app_user on purpose. app_user is an identity
--    and a credential; a name, a date of birth, a national id number are a
--    different kind of fact about the same person, asked for by a different
--    part of the system for a different reason. Mixing the two would mean
--    every query that only needs "who is this and what can they do" also
--    has to carry a person's date of birth past it.
--
-- WHY THE "GO" SEPARATORS - unchanged from V2. SQL Server compiles an entire
-- batch before running any of it, so a statement referring to something an
-- earlier statement in the same batch created fails to compile.


-- =============================================================================
-- 1. The login id an admin-created account is issued
-- =============================================================================
ALTER TABLE app_user ADD external_id NVARCHAR(20) NULL;
GO

CREATE UNIQUE INDEX uq_app_user_external_id
    ON app_user (external_id)
    WHERE external_id IS NOT NULL;
GO


-- =============================================================================
-- 2. One sequence per role that receives an auto-generated id
-- =============================================================================
CREATE SEQUENCE seq_student_id AS BIGINT START WITH 1 INCREMENT BY 1;
GO
CREATE SEQUENCE seq_teacher_id AS BIGINT START WITH 1 INCREMENT BY 1;
GO
CREATE SEQUENCE seq_admin_id   AS BIGINT START WITH 1 INCREMENT BY 1;
GO


-- =============================================================================
-- 3. Biographical detail - one row per student or teacher account
-- =============================================================================
CREATE TABLE person_profile (
    -- Shared primary key with app_user, not a surrogate id of its own: this
    -- row has no identity except "the profile belonging to this account",
    -- and a second id would only invite the question of whether the two
    -- could ever legitimately disagree. They cannot.
    app_user_id    BIGINT        NOT NULL,

    first_name     NVARCHAR(50)  NOT NULL,
    -- Optional - "if it exists", per the admin form's own wording. A
    -- mandatory middle name would produce a column full of placeholder text
    -- for the many people who do not have one, which is worse than an
    -- honest null.
    middle_name    NVARCHAR(50)  NULL,
    last_name      NVARCHAR(50)  NOT NULL,

    -- OMANG / national id. A real-world identifier, not one this system
    -- issues - UNIQUE because it is meant to name one specific person, and
    -- refusing a duplicate here is what catches "this is the same student
    -- entered twice" before it becomes two accounts.
    national_id    NVARCHAR(20)  NOT NULL,

    date_of_birth  DATE          NOT NULL,

    -- Contact information, never a login credential. A teacher's sign-in id
    -- is TCH20260001, generated the same way a student's is - see the file
    -- header for why an email is not reused as one.
    contact_email  NVARCHAR(100) NULL,

    date_enrolled  DATE          NOT NULL,

    version        BIGINT        NULL,

    CONSTRAINT pk_person_profile PRIMARY KEY (app_user_id),
    CONSTRAINT uq_person_profile_national_id UNIQUE (national_id),
    CONSTRAINT ck_person_profile_first_name_not_blank
        CHECK (LTRIM(RTRIM(first_name)) <> ''),
    CONSTRAINT ck_person_profile_last_name_not_blank
        CHECK (LTRIM(RTRIM(last_name)) <> ''),

    -- No ON DELETE CASCADE, the same policy as everywhere else in this
    -- schema (see V2/V4) - removing an account's profile as a side effect of
    -- removing the account must be an explicit act in the service, not a
    -- consequence nobody chose. AdminService.deleteTeacher deletes both,
    -- explicitly, in one transaction.
    CONSTRAINT fk_person_profile_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_user (id)
);
GO
