CREATE TABLE organization_units (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    parent_id BIGINT REFERENCES organization_units(id) ON DELETE RESTRICT,
    level SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 3),
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(parent_id, name)
);

INSERT INTO organization_units(name, parent_id, level)
VALUES ('未分配单位', NULL, 1);

ALTER TABLE equipment ADD COLUMN unit_id BIGINT REFERENCES organization_units(id);
UPDATE equipment SET unit_id = (SELECT id FROM organization_units WHERE name = '未分配单位' AND parent_id IS NULL);
ALTER TABLE equipment ALTER COLUMN unit_id SET NOT NULL;
CREATE INDEX idx_equipment_unit ON equipment(unit_id);
