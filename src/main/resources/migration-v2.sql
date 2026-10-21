-- Additive and safe to repeat on both Part 2 and fresh databases.
-- Legacy records have unknown origin; never invent their farm.
ALTER TABLE animal ADD COLUMN IF NOT EXISTS origin VARCHAR(200);
CREATE INDEX IF NOT EXISTS idx_animal_arrival_date ON animal(arrival_date);
CREATE INDEX IF NOT EXISTS idx_animal_origin ON animal(origin);
