-- V12: indexes for the lookups this application actually performs.
--
-- READ THIS BEFORE ADDING MORE
-- ---------------------------
-- An index is not free. Every one of them is a second structure the database
-- must update on every INSERT, UPDATE and DELETE to that table, and a page of
-- storage that has to be read and cached. "Add an index to make it faster" is
-- true for reads and false for writes, so the only defensible index is one
-- that serves a query somebody actually runs.
--
-- Most of this schema was already indexed by earlier migrations - the FK
-- columns on submission, course, enrolment, assessment, assignment and
-- audit_log all have one. What follows is the set that was genuinely missing,
-- found by reading every repository method in the codebase and comparing it
-- against sys.indexes rather than by adding one to each foreign key on
-- principle. Four columns SQL Server reports as "unindexed foreign keys" are
-- deliberately NOT here, and the reason is at the bottom of this file.
--
-- WHAT THIS WILL AND WILL NOT DO TODAY
-- ------------------------------------
-- The largest table in this database currently holds 51 rows. At that size the
-- optimizer will read the whole table regardless, because scanning 51 rows is
-- cheaper than descending an index to find them - so not one query plan will
-- change on the day this migration runs, and any claim of a speed-up now would
-- be false.
--
-- They are worth adding anyway, for a reason that is about time rather than
-- rows: adding an index to a table that has grown large is an expensive,
-- blocking operation performed under pressure, whereas adding it to a small
-- one is instant and free. audit_log in particular only ever grows - it is
-- append-only by design and nothing ever deletes from it - so it is the table
-- most certain to need this and the one where retrofitting later would hurt
-- most.


-- =============================================================================
-- 1. Foreign keys with a real query behind them
-- =============================================================================

-- AdminService.deleteTeacher asks "does this teacher own any assignment?"
-- (AssignmentRepository.existsByCreatedBy) before allowing the delete. Without
-- an index that is a full scan of every assignment ever set. The same column
-- is also what SQL Server must check to enforce fk_assignment_created_by when
-- an app_user row is deleted, which is the same scan for a different reason.
CREATE INDEX ix_assignment_created_by ON assignment (created_by_id);
GO

-- assessment.recorded_by_id has no finder method of its own, which is exactly
-- why it is easy to miss: the scan it causes is not in any repository, it is
-- inside the DELETE of an app_user, where the database checks
-- fk_assessment_recorded_by before permitting the row to go. A teacher who has
-- marked a lot of work is precisely the account that would be slowest to
-- delete, and the check is invisible in the application code.
CREATE INDEX ix_assessment_recorded_by ON assessment (recorded_by_id);
GO


-- =============================================================================
-- 2. The timetable added in V11
-- =============================================================================
-- V11 created ix_timetable_slot_term, because reading a timetable always means
-- "everything for this term". These two cover the other direction - the
-- dependency checks that let a blocked delete answer 409 with a sentence
-- instead of letting the constraint refuse it as a 500.
--
-- They live here rather than in V11 on purpose. V11 may already have been
-- applied by the time this lands, and an applied migration is never edited -
-- Flyway stores a checksum of every one precisely so it cannot be. A
-- correction, or an addition, gets its own version.

-- TimetableService.deletePeriod -> TimetableSlotRepository.existsByPeriod.
-- Not covered by either UNIQUE constraint: both lead with term_id and reach
-- period_id only as their third column, which cannot be seeked on alone.
CREATE INDEX ix_timetable_slot_period ON timetable_slot (period_id);
GO

-- SchoolService.deleteCourse and TimetableSlotRepository.existsByCourse.
-- course_id appears in both composite foreign keys but leads neither, so
-- nothing existing can seek it.
CREATE INDEX ix_timetable_slot_course ON timetable_slot (course_id);
GO


-- =============================================================================
-- 3. The audit log - the one table with no upper bound
-- =============================================================================
-- The admin audit page is server-paginated and filterable, and its query is
-- always the same shape: optionally narrow by entity_name and/or action,
-- optionally by a time range, then ORDER BY performed_at DESC and take one
-- page (AuditLogRepository.search).
--
-- The three existing indexes are each on a single column, so a filtered query
-- can use one of them to narrow OR performed_at to sort, but not both: the
-- other half becomes a sort of the whole matching set, and a sort is what
-- turns a fast page into a slow one as a table grows.
--
-- Leading with entity_name and carrying performed_at DESC inside it lets the
-- most common filtered read - "show me everything that happened to AppUser,
-- newest first" - be answered by walking one index in order and stopping after
-- a page. DESC matches the ORDER BY exactly; an ascending index would still be
-- usable backwards, but stating the real direction costs nothing.
--
-- NO INCLUDE COLUMNS, deliberately. Making this a covering index would mean
-- including the summary and the username, which together are most of the row -
-- at which point the index is a second copy of the table, paid for on every
-- single write. This table is written to by nearly every operation in the
-- system, so that trade is the wrong way round here.
CREATE INDEX ix_audit_log_entity_time ON audit_log (entity_name, performed_at DESC);
GO


-- =============================================================================
-- 4. The submission-status check behind a SERIALIZABLE transaction
-- =============================================================================
-- AssignmentService.deleteAssignment runs at SERIALIZABLE isolation to close a
-- race where a student could hand work in between the "has anyone submitted?"
-- check and the delete that follows it. That check is
-- existsByAssignmentAndStatus(assignment, SUBMITTED).
--
-- This index matters for a reason peculiar to that isolation level. Under
-- SERIALIZABLE, SQL Server takes RANGE locks over whatever it had to read to
-- answer the question - so the narrower the structure it reads, the narrower
-- the lock. Answering this from ix_submission_assignment alone means reading
-- every submission for that assignment and locking that whole range; answering
-- it from (assignment_id, status) locks only the submitted ones. Same result,
-- materially less of the table frozen while the transaction runs.
CREATE INDEX ix_submission_assignment_status ON submission (assignment_id, status);
GO


-- =============================================================================
-- WHAT IS DELIBERATELY NOT INDEXED, AND WHY
-- =============================================================================
-- Recorded here because "why is there no index on X" is a question somebody
-- will ask, and an answer is cheaper than the investigation.
--
-- THE *_role COLUMNS (assessment.student_role, assessment.recorded_by_role,
-- assignment.created_by_role, course.teacher_role, enrolment.student_role,
-- submission.student_role). A query listing unindexed foreign-key columns
-- reports all six, because each is part of a composite key to
-- app_user (id, role) and none of them leads an index. Indexing them would be
-- cargo cult: they hold one value each by CHECK constraint, so an index on
-- them has almost no selectivity, and every lookup that uses them is already
-- seeking on the id column beside them, which IS indexed.
--
-- term AND timetable_period. Both are small by nature - a school year has a
-- handful of terms and a school day a handful of periods - and both are read
-- in full every time they are read at all. An index would never be chosen, and
-- would cost a write on every edit for nothing. Their UNIQUE constraints
-- already cover the by-name and by-position lookups.
--
-- person_profile.contact_email. Nothing queries by it (see V11's decision not
-- to make it unique either); an index exists to serve a query, and there is no
-- query.
--
-- A COVERING INDEX FOR THE MARK BOOK. AssessmentService reads whole Assessment
-- rows, so covering would mean duplicating the row into the index. The
-- existing ix_assessment_student and ix_assessment_course already turn its
-- lookups into seeks, which is the part that matters.
