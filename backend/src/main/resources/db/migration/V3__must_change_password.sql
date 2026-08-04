-- V3: force a temporary password to be replaced (US-22).
--
-- An account created by a teacher (US-23) is issued with a password somebody
-- else chose and, briefly, knows. This flag marks such an account until its
-- owner has replaced that password, and the service refuses to do anything else
-- for them in the meantime.
--
-- WHY THE EXISTING ROWS GET 0 RATHER THAN 1.
-- The two seeded development accounts are backfilled to 0 - not pending - even
-- though they use a shared, published password. Setting them to 1 would be more
-- consistent in theory and wrong in practice: it would lock the demo and every
-- existing test out of the system on the next start, to protect credentials that
-- are already printed in the README. The seeded accounts are a known development
-- limitation recorded as PRD L9; this migration addresses the NEW risk, which is
-- a real person receiving a temporary password from somebody else.
--
-- ORDER MATTERS, as it did in V2: a column cannot be born NOT NULL on a
-- populated table. Add it nullable, backfill every existing row, and only then
-- tighten it - each step in its own GO batch, because SQL Server compiles a
-- whole batch before running any of it and would not see a column the same
-- batch had just added.

-- 1. Add the column, nullable for now.
ALTER TABLE app_user ADD must_change_password BIT NULL;
GO

-- 2. Backfill. Existing accounts are not pending a change; see the note above.
UPDATE app_user SET must_change_password = 0 WHERE must_change_password IS NULL;
GO

-- 3. Now it can carry its real constraint. A three-state flag - true, false or
--    "nobody set it" - would mean every read had to decide what null meant, and
--    different callers would decide differently.
ALTER TABLE app_user ALTER COLUMN must_change_password BIT NOT NULL;
GO

-- 4. A default for rows inserted by anything that does not name the column.
--    The application always sets it explicitly; this protects the invariant from
--    a direct INSERT, which is the same reasoning as every other constraint in
--    this schema - the rule belongs to the data, not to the writer.
ALTER TABLE app_user
    ADD CONSTRAINT df_app_user_must_change_password
    DEFAULT 0 FOR must_change_password;
GO
