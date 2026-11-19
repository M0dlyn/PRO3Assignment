-- Used only by Part 4 station/central processes; separate from legacy Parts 2/3 mode.
ALTER TABLE tray_batch ADD COLUMN IF NOT EXISTS closed BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE IF NOT EXISTS product_part (
  part_id VARCHAR(100) PRIMARY KEY REFERENCES part(part_id),
  product_id VARCHAR(100) NOT NULL REFERENCES product(product_id)
);
CREATE INDEX IF NOT EXISTS idx_product_part_product ON product_part(product_id);
CREATE TABLE IF NOT EXISTS node_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  node_role VARCHAR(20) NOT NULL,
  cursor_value BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS outbox (
  sequence_number BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id VARCHAR(36) NOT NULL UNIQUE,
  event_json CLOB NOT NULL,
  delivered BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS inbox (
  event_id VARCHAR(36) PRIMARY KEY,
  event_json CLOB NOT NULL
);
