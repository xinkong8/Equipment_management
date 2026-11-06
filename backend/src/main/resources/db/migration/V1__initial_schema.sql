CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE equipment_categories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE category_fields (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id BIGINT NOT NULL REFERENCES equipment_categories(id) ON DELETE CASCADE,
    field_name VARCHAR(100) NOT NULL,
    field_key VARCHAR(100) NOT NULL,
    field_type VARCHAR(20) NOT NULL CHECK (field_type IN ('TEXT', 'TEXTAREA', 'INTEGER', 'DECIMAL', 'DATE', 'DATETIME', 'BOOLEAN', 'SELECT')),
    required BOOLEAN NOT NULL DEFAULT FALSE,
    options JSONB,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(category_id, field_key)
);

CREATE TABLE equipment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    equipment_code VARCHAR(30) NOT NULL UNIQUE,
    category_id BIGINT NOT NULL REFERENCES equipment_categories(id),
    name VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'IN_STOCK' CHECK (status IN ('IN_STOCK', 'IN_TASK', 'MAINTENANCE', 'DISABLED')),
    location VARCHAR(200),
    department VARCHAR(200),
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by BIGINT NOT NULL REFERENCES users(id),
    updated_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at TIMESTAMPTZ
);

CREATE TABLE tasks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(100),
    location VARCHAR(200),
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'EXECUTING' CHECK (status IN ('PLANNED', 'EXECUTING', 'COMPLETED', 'CANCELLED')),
    leader VARCHAR(100),
    participants TEXT,
    description TEXT,
    created_by BIGINT NOT NULL REFERENCES users(id),
    updated_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE task_equipment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES tasks(id),
    equipment_id BIGINT NOT NULL REFERENCES equipment(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    returned_at TIMESTAMPTZ,
    assigned_user VARCHAR(100),
    returned_user VARCHAR(100),
    return_status VARCHAR(30),
    usage_description TEXT,
    mileage NUMERIC(12, 2),
    damage_description TEXT,
    transfer_description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_task_equipment_open_assignment ON task_equipment(equipment_id) WHERE returned_at IS NULL;

CREATE TABLE equipment_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    equipment_id BIGINT NOT NULL REFERENCES equipment(id),
    task_equipment_id BIGINT REFERENCES task_equipment(id),
    event_type VARCHAR(30) NOT NULL,
    old_status VARCHAR(30),
    new_status VARCHAR(30),
    operator_id BIGINT REFERENCES users(id),
    handler_name VARCHAR(100),
    description TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE attachments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_type VARCHAR(100),
    file_size BIGINT,
    equipment_id BIGINT REFERENCES equipment(id),
    task_id BIGINT REFERENCES tasks(id),
    event_id BIGINT REFERENCES equipment_events(id),
    uploaded_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (num_nonnulls(equipment_id, task_id, event_id) = 1)
);

CREATE INDEX idx_equipment_category ON equipment(category_id);
CREATE INDEX idx_equipment_status ON equipment(status);
CREATE INDEX idx_equipment_created_by ON equipment(created_by);
CREATE INDEX idx_equipment_events_equipment ON equipment_events(equipment_id, occurred_at DESC);
CREATE INDEX idx_tasks_status ON tasks(status);
