-- V2: accounts, roles, and assignment ownership.
--
-- This migration introduces the first RELATIONSHIP in the schema. Until now
-- there was one table and no foreign keys, so "referential integrity" had
-- nothing to protect. It does now.
--
-- ORDER MATTERS. The owner column cannot be NOT NULL from the start, because
-- rows already exist and every one of them would violate it. The sequence is
-- the standard one for adding a mandatory relationship to a populated table:
--   1. create the parent table
--   2. add the column as nullable
--   3. give existing children an owner (backfill)
--   4. only then tighten to NOT NULL and add the foreign key
--
-- WHY THE "GO" SEPARATORS.
-- SQL Server compiles an entire batch before running any of it. Adding a column
-- and then referring to that column in the same batch fails to compile with
-- "Invalid column name", even though the ALTER would have created it first.
-- GO ends the batch, so each statement below is compiled against the schema the
-- previous batch actually left behind. This is a SQL Server behaviour, not a
-- Flyway one, and it is the reason this file is not one continuous script.

-- 1. Accounts -----------------------------------------------------------------
CREATE TABLE app_user (
    id            BIGINT        IDENTITY(1,1) NOT NULL,
    username      NVARCHAR(50)  NOT NULL,
    password_hash NVARCHAR(100) NOT NULL,
    role          NVARCHAR(20)  NOT NULL,
    version       BIGINT        NULL,

    CONSTRAINT pk_app_user PRIMARY KEY (id),

    -- Uniqueness belongs here, not in a "check then insert" in Java. Two
    -- simultaneous registrations could both pass an application-level check
    -- before either wrote; the database can refuse the second one outright.
    CONSTRAINT uq_app_user_username UNIQUE (username),

    -- The closed set of roles, enforced by the database as well as the enum.
    CONSTRAINT ck_app_user_role CHECK (role IN ('STUDENT', 'TEACHER'))
);
GO

-- Seed the two development accounts. The password_hash here is a PLACEHOLDER:
-- a hash literal in SQL is a hostage to whatever cost factor was current when it
-- was typed. TrackerApplication.seedAccounts replaces it at startup using the
-- application's own PasswordEncoder, so the stored hash always matches the
-- configuration actually in force.
INSERT INTO app_user (username, password_hash, role, version) VALUES
    ('teacher', 'placeholder-replaced-at-startup', 'TEACHER', 0),
    ('student', 'placeholder-replaced-at-startup', 'STUDENT', 0);
GO

-- 2. Ownership on assignment ---------------------------------------------------
ALTER TABLE assignment ADD owner_id BIGINT NULL;
GO

-- Backfill: existing assignments predate accounts, so they are handed to the
-- teacher. Doing this before the NOT NULL below is what makes the tightening
-- possible at all.
UPDATE assignment
SET owner_id = (SELECT id FROM app_user WHERE username = 'teacher')
WHERE owner_id IS NULL;
GO

-- 3. Now the column can carry its real constraints.
ALTER TABLE assignment ALTER COLUMN owner_id BIGINT NOT NULL;
GO

ALTER TABLE assignment
    ADD CONSTRAINT fk_assignment_owner
    FOREIGN KEY (owner_id) REFERENCES app_user (id);
GO
-- Deliberately NO "ON DELETE CASCADE". Destroying somebody's work as a side
-- effect of removing their account should be an explicit decision taken in the
-- service, not something the schema does quietly. Without a cascade rule the
-- database refuses to delete an account that still owns assignments - which is
-- exactly the safe default.

CREATE INDEX ix_assignment_owner ON assignment (owner_id);
GO

-- 4. Due dates (EPIC-07) -------------------------------------------------------
-- Nullable: an assignment without a deadline is legitimate, not missing data.
ALTER TABLE assignment ADD due_date DATE NULL;
GO
