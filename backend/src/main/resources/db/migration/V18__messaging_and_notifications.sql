-- V18: messages between people, and notifications the system raises by itself.
--
-- TWO TABLES, NOT ONE, BECAUSE THEY ARE NOT THE SAME THING
-- -------------------------------------------------------
-- A MESSAGE is written by a person to a person. It has an author who can be
-- held to it, it is correspondence, and the only rule about it is who is
-- allowed to write to whom.
--
-- A NOTIFICATION is raised by the system about something that happened to a
-- record - a mark was posted, a lesson was missed, a due date is close. Nobody
-- wrote it, it cannot be replied to, and the hard problem is not permission
-- but DUPLICATION: the thing that raises it runs repeatedly.
--
-- Folding both into one table would mean a nullable sender, a nullable subject,
-- and a type column deciding which half of the row is meaningful - the shape
-- that always ends up with rows that satisfy the constraints and still make no
-- sense. They are separate, and each is fully constrained.
--
--
-- WHY notification HAS NO FOREIGN KEY TO WHAT IT IS ABOUT
-- ------------------------------------------------------
-- subject_type/subject_id say "this is about assessment 41" so the interface
-- can offer a link. They are deliberately NOT a foreign key, and this is the
-- second deviation from "constrain at the database" in this schema - the first
-- being audit_log.performed_by_id, for exactly the same reason.
--
-- A real reference would invert the dependency. Telling a student their mark
-- was posted would make that mark undeletable, and telling them a due date is
-- close would pin the assignment open until every notification about it was
-- cleared. A notification must never be the reason a real record cannot be
-- corrected.
--
-- What makes that safe is that the notification does not NEED the row: title
-- and body are a snapshot, written when it was raised, exactly as
-- audit_log.summary is. It stays readable and truthful after its subject is
-- gone, and the interface treats a dead link as a link to nothing rather than
-- an error.
--
--
-- WHY dedupe_key IS NOT NULL AND UNIQUE PER RECIPIENT
-- --------------------------------------------------
-- This is the constraint that carries the feature.
--
-- "Tell a student their work is due soon" is produced by a job on a timer. It
-- has no memory, it re-reads the same assignments every run, and the naive
-- version notifies the same student about the same assignment every time it
-- wakes up. That is not a cosmetic problem - it is the notification equivalent
-- of taking a register twice and getting two registers, which V14 refused to
-- allow for the same reason.
--
-- Every notification therefore names the EVENT it is about, not the moment it
-- was raised: 'MARK:41', 'ABSENCE:903', 'DUE_SOON:512', 'MESSAGE:77'. One row
-- per recipient per event, enforced here rather than by the job remembering
-- what it has already done. The job may run as often as it likes, twice at
-- once, or after a crash halfway through, and the answer does not change.
--
-- NOT NULL, so this is a plain UNIQUE rather than the filtered index that
-- assessment.submission_id needs - SQL Server treats NULLs as equal, so a
-- nullable key would have permitted exactly one keyless notification in the
-- whole table. Every notification has an event, so there is no null case.
--
--
-- ROLE PINNING carries no CHECK here, unlike every other use of the technique
-- in this schema (V4, V14). Every role may send, receive and be notified, so
-- there is no role to restrict to. The composite foreign key still earns its
-- place: it makes the role column agree with the account's REAL role, so the
-- interface can badge a sender without a join and the badge cannot be wrong.


-- =============================================================================
-- message - written by a person, to a person
-- =============================================================================
CREATE TABLE message (
    id             BIGINT       IDENTITY(1,1) NOT NULL,

    sender_id      BIGINT       NOT NULL,
    sender_role    NVARCHAR(20) NOT NULL,

    recipient_id   BIGINT       NOT NULL,
    recipient_role NVARCHAR(20) NOT NULL,

    body           NVARCHAR(2000) NOT NULL,

    sent_at        DATETIMEOFFSET NOT NULL,
    -- NULL until the recipient opens it. The presence of a time is the whole
    -- read/unread state; there is no separate boolean to disagree with it.
    read_at        DATETIMEOFFSET NULL,

    version        BIGINT       NULL,

    CONSTRAINT pk_message PRIMARY KEY (id),

    -- Writing to yourself is not a use of a messaging system, it is a note to
    -- self, and allowing it would put a conversation with nobody in every
    -- inbox. The service refuses it with a sentence; this makes it impossible.
    CONSTRAINT ck_message_not_self CHECK (sender_id <> recipient_id),

    -- Empty correspondence is not correspondence. LEN() ignores trailing
    -- spaces in SQL Server, so a body of only spaces fails this too, which is
    -- the intent - the service trims before it ever gets here.
    CONSTRAINT ck_message_body_not_blank CHECK (LEN(body) > 0),

    -- A read time before the send time is not a correction, it is corruption.
    CONSTRAINT ck_message_read_after_sent CHECK (read_at IS NULL OR read_at >= sent_at),

    CONSTRAINT fk_message_sender
        FOREIGN KEY (sender_id, sender_role) REFERENCES app_user (id, role),
    CONSTRAINT fk_message_recipient
        FOREIGN KEY (recipient_id, recipient_role) REFERENCES app_user (id, role)
);
GO

-- "My inbox, newest first" - the query the messages page opens with.
CREATE INDEX ix_message_recipient_sent ON message (recipient_id, sent_at DESC);
GO

-- "What I have sent" - and the other half of a conversation view, which reads
-- both directions and merges them.
CREATE INDEX ix_message_sender_sent ON message (sender_id, sent_at DESC);
GO


-- =============================================================================
-- notification - raised by the system, about a record
-- =============================================================================
CREATE TABLE notification (
    id             BIGINT       IDENTITY(1,1) NOT NULL,

    recipient_id   BIGINT       NOT NULL,
    recipient_role NVARCHAR(20) NOT NULL,

    type           NVARCHAR(30) NOT NULL,

    -- The snapshot. Written once, never recomputed, readable forever - see the
    -- header on why this is what lets subject_id carry no foreign key.
    title          NVARCHAR(150) NOT NULL,
    body           NVARCHAR(500) NOT NULL,

    -- What it is about. Deliberately not a foreign key; see the file header.
    subject_type   NVARCHAR(30) NULL,
    subject_id     BIGINT       NULL,

    -- The event this notification is FOR - see the file header.
    dedupe_key     NVARCHAR(120) NOT NULL,

    created_at     DATETIMEOFFSET NOT NULL,
    read_at        DATETIMEOFFSET NULL,

    version        BIGINT       NULL,

    CONSTRAINT pk_notification PRIMARY KEY (id),

    -- ONE NOTIFICATION PER RECIPIENT PER EVENT. The whole reason the timer job
    -- can be written without any memory of its own.
    CONSTRAINT uq_notification_recipient_event UNIQUE (recipient_id, dedupe_key),

    -- EnumType.STRING on the Java side; these are the four constants of
    -- NotificationType, spelled out so the database refuses anything else
    -- whatever wrote it.
    CONSTRAINT ck_notification_type CHECK (type IN
        ('DUE_SOON', 'MARK_POSTED', 'ABSENCE_RECORDED', 'MESSAGE_RECEIVED')),

    -- A subject is a pair or it is nothing. Half a reference - a type naming
    -- no row, or an id belonging to no table - is unusable by the interface
    -- and impossible to interpret later.
    CONSTRAINT ck_notification_subject_paired CHECK (
        (subject_type IS NULL AND subject_id IS NULL)
        OR (subject_type IS NOT NULL AND subject_id IS NOT NULL)),

    CONSTRAINT ck_notification_title_not_blank CHECK (LEN(title) > 0),
    CONSTRAINT ck_notification_body_not_blank CHECK (LEN(body) > 0),
    CONSTRAINT ck_notification_read_after_created
        CHECK (read_at IS NULL OR read_at >= created_at),

    CONSTRAINT fk_notification_recipient
        FOREIGN KEY (recipient_id, recipient_role) REFERENCES app_user (id, role)
);
GO

-- "My notifications, newest first" - the page, and the bell's own query.
CREATE INDEX ix_notification_recipient_created
    ON notification (recipient_id, created_at DESC);
GO

-- The unread count sits in the app bar and is therefore requested on nearly
-- every navigation. Filtered to unread rows only, so the index holds just the
-- handful of rows the count is over rather than every notification ever
-- raised - the count stays the same size as the badge it feeds.
CREATE INDEX ix_notification_unread
    ON notification (recipient_id) WHERE read_at IS NULL;
GO
