-- Test-only counterpart to V8's three sequences.
--
-- The test schema is built from JPA annotations (ddl-auto=create-drop),
-- since Flyway is off in tests - see application-test.properties. That
-- covers every @Entity's table, but a free-standing SEQUENCE has no
-- annotation that creates one as a side effect, so it has to be added here.
-- Spring Boot runs schema.sql automatically against an embedded database
-- (H2 qualifies) with no extra configuration needed.
CREATE SEQUENCE IF NOT EXISTS seq_student_id AS BIGINT START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_teacher_id AS BIGINT START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS seq_admin_id   AS BIGINT START WITH 1 INCREMENT BY 1;
