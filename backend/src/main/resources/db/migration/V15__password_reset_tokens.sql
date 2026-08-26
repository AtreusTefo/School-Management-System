-- V15: single-use, time-limited password reset tokens.
--
-- THE TOKEN ITSELF IS NEVER STORED
-- --------------------------------
-- token_hash holds a SHA-256 of the value that was emailed; the value itself
-- exists only in the email and in the browser of whoever received it. A reset
-- token is a bearer credential - anyone holding one can take over the account
-- it belongs to - so storing it in readable form would mean a leaked database
-- backup handed over every account with a live token, without needing to break
-- a single password hash.
--
-- WHY SHA-256 AND NOT BCRYPT, WHEN PASSWORDS USE BCRYPT
-- BCrypt is deliberately slow and salts every row differently, which is right
-- for a human-chosen password: the salt stops one rainbow table breaking every
-- row at once, and the cost makes guessing expensive. Neither applies here.
-- A token is 256 bits from a cryptographic RNG, so there is nothing to guess
-- and no dictionary to build - and a per-row salt would make LOOKUP impossible,
-- because finding the row would mean BCrypt-comparing the presented token
-- against every outstanding token in the table. A fast, deterministic hash is
-- both correct and required here.
--
-- WHY used_at RATHER THAN DELETING THE ROW
-- A used token is evidence. Keeping it, marked, means "this link was already
-- used" is answerable - which is a materially different message from "this link
-- is not valid", and the difference matters when somebody is trying to work out
-- whether they or an attacker used it. The rows are cleaned up by expiry, not
-- by consumption.
--
-- NO FOREIGN KEY BACK TO... actually there IS one, unlike audit_log. The
-- distinction is worth stating because the two look similar: an audit entry
-- must outlive the account it names, so it stores a snapshot. A reset token is
-- meaningless once its account is gone, so it references the account for real
-- and is removed with it - AdminService deletes them explicitly, in the same
-- transaction, exactly as it already does for enrolments.

CREATE TABLE password_reset_token (
    id           BIGINT         IDENTITY(1,1) NOT NULL,

    -- 64 hex characters - SHA-256. Never the token itself. See the header.
    token_hash   NVARCHAR(64)   NOT NULL,

    app_user_id  BIGINT         NOT NULL,

    created_at   DATETIMEOFFSET NOT NULL,
    expires_at   DATETIMEOFFSET NOT NULL,
    -- NULL until the token is spent. Not deleted on use - see the header.
    used_at      DATETIMEOFFSET NULL,

    version      BIGINT         NULL,

    CONSTRAINT pk_password_reset_token PRIMARY KEY (id),

    -- Two rows sharing a hash would mean two accounts resettable by one link.
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash),

    -- A token that expires before it is issued can never be used, which makes
    -- it a corrupt row rather than a very short-lived one.
    CONSTRAINT ck_password_reset_token_expiry CHECK (expires_at > created_at),

    CONSTRAINT fk_password_reset_token_user
        FOREIGN KEY (app_user_id) REFERENCES app_user (id)
);
GO

-- "Every outstanding token for this account", which is what issuing a new one
-- and consuming one both need. The UNIQUE index above already serves lookup by
-- hash, which is the other access path.
CREATE INDEX ix_password_reset_token_user ON password_reset_token (app_user_id);
GO
