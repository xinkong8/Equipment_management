SELECT setval(
    'equipment_code_seq',
    COALESCE(
        (SELECT MAX(CAST(SUBSTRING(equipment_code FROM 4) AS BIGINT))
         FROM equipment
         WHERE equipment_code ~ '^EQ-[0-9]+$'),
        0
    ),
    EXISTS (SELECT 1 FROM equipment WHERE equipment_code ~ '^EQ-[0-9]+$')
);
