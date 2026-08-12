-- V9: a contact phone number on person_profile.
--
-- NULLABLE, for the same reason contact_email is: the profiles that already
-- exist predate this column and have no value to backfill with that would be
-- honest - inventing one would look like real contact detail. Every profile
-- saved through the admin forms FROM NOW ON is required to carry one (see
-- AdminController.PersonRequest / UpdatePersonRequest); the column stays
-- nullable at the database only to admit that pre-existing gap, not to make
-- the field optional going forward.
--
-- FORMAT, WHEN PRESENT, IS ENFORCED HERE
-- +267 followed by exactly eight digits - a Botswana mobile number in E.164
-- form. The application defaults the +267 in the UI and asks only for the
-- eight digits, but the constraint checks the whole stored value regardless
-- of which layer produced it, the same reasoning ck_assessment_score_within_max
-- already applies: the check is what makes the shape true of the DATA, not
-- just of the one form that currently writes it.
--
-- Each `[0-9]` matches exactly one character. LIKE has no `{n}` repetition
-- operator, so the eight digits are spelled out one placeholder at a time
-- rather than approximated with a wildcard that would also accept a ninth.

ALTER TABLE person_profile ADD phone NVARCHAR(20) NULL;
GO

ALTER TABLE person_profile ADD CONSTRAINT ck_person_profile_phone_format
    CHECK (phone IS NULL OR phone LIKE '+267[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]');
GO
