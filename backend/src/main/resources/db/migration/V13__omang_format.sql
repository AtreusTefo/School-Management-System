-- V13: the Omang (Botswana national identity number) has a fixed shape.
--
-- THE RULE
-- --------
--   exactly 9 characters
--   digits only
--   the 5th digit is 1 (male) or 2 (female)
--
-- The 5th digit is the gender marker. Validating it is not cosmetic: it is the
-- one position in the number that is self-describing, so a transposition or a
-- mistyped digit in the middle of an otherwise plausible number is caught here
-- rather than surviving as a record attached to the wrong person.
--
-- (An older registration convention used a 7th-digit marker, 0-4 female and
-- 5-9 male. This constraint implements the modern rule, checking position 5
-- for a 1 or a 2, which is what current digital platforms validate against.)
--
--
-- WHY THIS IS "WITH NOCHECK", WHICH IS OTHERWISE AGAINST THIS SCHEMA'S HABITS
-- ---------------------------------------------------------------------------
-- Every other constraint in this database is applied over its existing data
-- and is therefore trusted. This one cannot be, and pretending otherwise would
-- have meant one of two worse things.
--
-- At the time of writing, 6 of the 7 profiles on record carry a national_id
-- that this rule rejects - four of 12 digits, two of 13. They belong to real
-- accounts created through the admin panel (STU20260001-4, TCH20260001-3) with
-- real names attached. So the two alternatives were:
--
--   Apply the constraint normally. The migration FAILS - SQL Server validates
--   existing rows for a plain ADD CONSTRAINT - and the application will not
--   start at all until somebody empties or edits those rows under pressure.
--
--   Rewrite the six numbers to something that passes. That is fabricating a
--   government identity number for a named person, which is worse than a
--   wrong one: a wrong number is visibly wrong, an invented one is not.
--
-- WITH NOCHECK takes neither. The existing rows are grandfathered exactly as
-- they are, and every write from here on is checked. Verified empirically
-- against this database before being written, because the behaviour is easy to
-- assume wrongly:
--
--   editing another column of a legacy row      ALLOWED  (stays editable)
--   writing a bad national_id, insert or update BLOCKED
--   writing a valid 9-digit Omang               ALLOWED
--
-- That last pair is the point. The six legacy rows are readable and editable,
-- but the moment anyone corrects one of those numbers it must be a real Omang.
-- The bad data is contained rather than blessed, and it cannot spread.
--
-- The constraint will report as is_not_trusted = 1 in sys.check_constraints,
-- which is accurate and should stay that way until the six are corrected by
-- somebody who knows the real numbers. To make it trusted afterwards:
--   ALTER TABLE person_profile WITH CHECK CHECK CONSTRAINT ck_person_profile_omang;
-- which re-validates every row and fails while any still violates it.

ALTER TABLE person_profile WITH NOCHECK ADD CONSTRAINT ck_person_profile_omang
    CHECK (
        LEN(national_id) = 9
        AND national_id NOT LIKE '%[^0-9]%'
        AND SUBSTRING(national_id, 5, 1) IN ('1', '2')
    );
GO
