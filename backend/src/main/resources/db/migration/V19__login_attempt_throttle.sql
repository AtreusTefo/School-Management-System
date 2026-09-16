-- V19: a record of FAILED sign-in attempts, so guessing can be slowed down.
--
-- WHAT THIS CLOSES
-- ----------------
-- Until now POST /api/auth/login could be called as fast as the network
-- allowed. BCrypt at cost 10 makes each attempt cost something, which is real
-- protection against an offline crack of a stolen hash - but it is not
-- protection against an attacker who simply keeps asking. Nothing counted, so
-- nothing ever said no.
--
-- The seeded development accounts are gone from production (TrackerApplication
-- gates every seed runner @Profile("!prod")), so the KNOWN passwords are gone.
-- This is the other half: making the unknown ones expensive to find.
--
--
-- WHY THERE IS NO FOREIGN KEY TO app_user, AND WHY THAT IS A SECURITY
-- PROPERTY RATHER THAN A CONVENIENCE
-- -------------------------------------------------------------------
-- This is the third and last table in this schema that deliberately omits a
-- foreign key, after audit_log.performed_by_id and notification.subject_id.
-- The first two omit theirs so that a log entry cannot make an account
-- undeletable. This one omits it for a different and stronger reason.
--
-- MOST FAILED ATTEMPTS ARE FOR USERNAMES THAT DO NOT EXIST. That is what a
-- guessing attack looks like: STU20260001, STU20260002, STU20260003. A foreign
-- key would make precisely those rows - the ones that matter most - impossible
-- to record.
--
-- It is also what keeps the throttle from becoming an account-enumeration
-- oracle. Because a failure is counted for ANY attempted username, real or
-- not, ten guesses at 'admin' and ten guesses at 'qqqqqq' both end in the same
-- 429. If only real accounts were counted, the difference between 429 and 401
-- would be a free, reliable way to discover which sign-in ids exist - which is
-- the exact thing PasswordResetService's identical-answer rule was built to
-- prevent, undone from a different direction.
--
--
-- WHY BOTH username AND ip_address ARE COUNTED
-- --------------------------------------------
-- They stop different attacks and neither is sufficient alone.
--
--   username    stops one account being ground down. This is the strong one.
--   ip_address  stops SPRAYING - one common password tried against hundreds of
--               different accounts, where no single username ever reaches its
--               own limit.
--
-- The per-IP limit is deliberately the looser of the two, because a school is
-- the NAT case: an entire computer lab reaches this server from one public
-- address, and a limit tight enough to stop a determined sprayer would lock
-- out a classroom on a Monday morning. Treat per-IP as a blunt instrument that
-- catches the careless, and the per-username limit as the rule that actually
-- holds.
--
-- Neither column is a foreign key, and ip_address is stored as text rather
-- than parsed: it is evidence of what arrived, not a reference to anything.
--
--
-- WHY ROWS ARE KEPT AFTER THE WINDOW PASSES
-- -----------------------------------------
-- The throttle only ever reads the last few minutes, so it would be enough to
-- delete a row the moment it stopped counting. They are kept for a day instead
-- (LoginAttemptService.purgeOld, hourly) because "was this account attacked
-- last night" is a question somebody will eventually need to answer, and a
-- table that has already forgotten cannot answer it.

CREATE TABLE login_attempt (
    id BIGINT IDENTITY(1,1) NOT NULL,

    -- The username AS TYPED. Length matches app_user.username, because a
    -- longer string cannot match any account and is rejected at the edge
    -- before it ever reaches here.
    username NVARCHAR(50) NOT NULL,

    -- 45 characters is the longest an IPv6 address can be written as,
    -- including an embedded IPv4 tail.
    ip_address NVARCHAR(45) NOT NULL,

    attempted_at DATETIMEOFFSET NOT NULL,

    CONSTRAINT pk_login_attempt PRIMARY KEY (id),
    CONSTRAINT ck_login_attempt_username_not_blank CHECK (LEN(username) > 0),
    CONSTRAINT ck_login_attempt_ip_not_blank CHECK (LEN(ip_address) > 0)
);
GO

-- The two questions the throttle asks on EVERY sign-in attempt, including the
-- successful ones. Both are "count the rows for this key since a moment", so
-- both indexes lead on the key and carry the time descending.
CREATE INDEX ix_login_attempt_username ON login_attempt (username, attempted_at DESC);
GO

CREATE INDEX ix_login_attempt_ip ON login_attempt (ip_address, attempted_at DESC);
GO
