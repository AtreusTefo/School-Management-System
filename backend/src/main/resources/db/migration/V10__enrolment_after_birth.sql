-- V10: a person cannot enrol before they are born.
--
-- Nothing before this checked the relationship between date_of_birth and
-- date_enrolled at all - each was validated independently (not null, not in
-- the future) with no rule comparing the two. A profile recording someone
-- enrolling before their own date of birth is not a high number, it is a
-- corrupt row, in the same sense CLAUDE.md already states for a mark that
-- exceeds its maximum: the constraint is what makes the shape true of the
-- DATA, not just of the one form that currently writes it.

ALTER TABLE person_profile ADD CONSTRAINT ck_person_profile_enrolled_after_birth
    CHECK (date_enrolled >= date_of_birth);
GO
