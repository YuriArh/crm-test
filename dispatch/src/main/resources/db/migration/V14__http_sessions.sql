-- Sessions of signed-in users (docs/specs/jdbc-sessions.md §2.2): the tables of Spring Session JDBC, so that a session
-- survives a restart of dispatch.
--
-- The layout is the framework's reference script for PostgreSQL and deliberately does not follow the schema rules of
-- the project: the key is char(36), not uuid; times are epoch milliseconds in bigint, not timestamptz; and "now" comes
-- from the system clock, not from the Clock bean. These are the framework's tables: its SQL fixes their format, and
-- bending them would mean writing a session store of our own. No business code reads or writes them.
-- Constraints and indexes are named explicitly, as the project requires.

CREATE TABLE spring_session (
    primary_id            char(36) NOT NULL,
    session_id            char(36) NOT NULL,
    creation_time         bigint   NOT NULL,
    last_access_time      bigint   NOT NULL,
    max_inactive_interval integer  NOT NULL,
    expiry_time           bigint   NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

-- The id the cookie carries; it changes at sign-in, the primary id does not.
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
-- Clean-up of expired sessions.
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
-- Sessions of one user (not used yet: end all sessions of a user, docs/specs/jdbc-sessions.md §5).
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
