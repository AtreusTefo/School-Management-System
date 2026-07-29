-- V1: the assignment table.
--
-- This is the baseline: the schema the application had while it ran on H2,
-- expressed as SQL Server DDL so it can be created deliberately and repeatably.
--
-- ONE THING DID NOT SURVIVE THE MOVE UNCHANGED.
-- On H2 the status column was declared `enum ('IN_PROGRESS','SUBMITTED')`, a
-- native type that refuses any other value. SQL Server has no ENUM type, so
-- Hibernate maps @Enumerated(STRING) to a plain varchar - and a plain varchar
-- would accept 'banana' happily. The CHECK constraint below restores the
-- guarantee that US-09 claims: an invalid status is rejected by the DATABASE,
-- not merely by the application that happens to be writing.

CREATE TABLE assignment (
    id      BIGINT        IDENTITY(1,1) NOT NULL,
    version BIGINT        NULL,
    title   NVARCHAR(200) NOT NULL,
    status  NVARCHAR(20)  NOT NULL,

    CONSTRAINT pk_assignment PRIMARY KEY (id),

    -- The database's own copy of the "closed set of values" rule.
    CONSTRAINT ck_assignment_status
        CHECK (status IN ('IN_PROGRESS', 'SUBMITTED')),

    -- A blank title is not a valid title. @NotBlank enforces this in Java;
    -- this makes it true of the data regardless of the writer.
    CONSTRAINT ck_assignment_title_not_blank
        CHECK (LEN(LTRIM(RTRIM(title))) > 0)
);

-- Listing is the most common read, and the UI shows outstanding work first.
CREATE INDEX ix_assignment_status ON assignment (status);
