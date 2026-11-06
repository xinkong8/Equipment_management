CREATE SEQUENCE task_code_seq START WITH 1;

DO $$
DECLARE
    last_code BIGINT;
BEGIN
    SELECT MAX(CAST(SUBSTRING(task_code FROM 6) AS BIGINT)) INTO last_code
    FROM tasks WHERE task_code ~ '^TASK-[0-9]+$';
    IF last_code IS NOT NULL THEN
        PERFORM setval('task_code_seq', last_code, TRUE);
    END IF;
END $$;
