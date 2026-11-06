ALTER TABLE users
    ADD COLUMN work_unit VARCHAR(200),
    ADD COLUMN job_title VARCHAR(100),
    ADD COLUMN phone VARCHAR(50),
    ADD COLUMN email VARCHAR(150),
    ADD COLUMN address VARCHAR(300),
    ADD COLUMN remarks TEXT;

-- The system administrator is displayed consistently as “管理者”.
UPDATE users SET name = '管理者' WHERE role = 'ADMIN';
