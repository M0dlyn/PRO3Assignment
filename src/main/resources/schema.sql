-- Part 1 animalId is the registration number; identifiers are case-sensitive.
CREATE TABLE IF NOT EXISTS animal (
  registration_number VARCHAR(100) PRIMARY KEY,
  species VARCHAR(100) NOT NULL,
  arrival_weight_kg DECIMAL(12,3) NOT NULL CHECK (arrival_weight_kg > 0),
  arrival_date DATE NOT NULL
);
CREATE TABLE IF NOT EXISTS part_type (name VARCHAR(100) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS tray (
  tray_id VARCHAR(100) PRIMARY KEY,
  maximum_weight_kg DECIMAL(12,3) NOT NULL CHECK (maximum_weight_kg > 0)
);
CREATE TABLE IF NOT EXISTS tray_batch (
  batch_id VARCHAR(100) PRIMARY KEY,
  tray_id VARCHAR(100) NOT NULL REFERENCES tray(tray_id),
  part_type VARCHAR(100) NOT NULL REFERENCES part_type(name),
  created_at TIMESTAMP NOT NULL,
  UNIQUE (batch_id, part_type)
);
CREATE TABLE IF NOT EXISTS part (
  part_id VARCHAR(100) PRIMARY KEY,
  animal_registration_number VARCHAR(100) NOT NULL REFERENCES animal(registration_number),
  batch_id VARCHAR(100) NOT NULL,
  part_type VARCHAR(100) NOT NULL REFERENCES part_type(name),
  weight_kg DECIMAL(12,3) NOT NULL CHECK (weight_kg > 0),
  FOREIGN KEY (batch_id, part_type) REFERENCES tray_batch(batch_id, part_type)
);
CREATE TABLE IF NOT EXISTS product_type (name VARCHAR(100) PRIMARY KEY);
CREATE TABLE IF NOT EXISTS product_requirement (
  product_type VARCHAR(100) NOT NULL REFERENCES product_type(name),
  part_type VARCHAR(100) NOT NULL REFERENCES part_type(name),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (product_type, part_type)
);
CREATE TABLE IF NOT EXISTS product (
  product_id VARCHAR(100) PRIMARY KEY,
  product_type VARCHAR(100) NOT NULL REFERENCES product_type(name),
  packed_date DATE NOT NULL
);
CREATE TABLE IF NOT EXISTS product_tray_batch (
  product_id VARCHAR(100) NOT NULL REFERENCES product(product_id),
  batch_id VARCHAR(100) NOT NULL REFERENCES tray_batch(batch_id),
  PRIMARY KEY (product_id, batch_id)
);
CREATE INDEX IF NOT EXISTS idx_part_animal ON part(animal_registration_number);
CREATE INDEX IF NOT EXISTS idx_part_batch ON part(batch_id);
CREATE INDEX IF NOT EXISTS idx_product_batch ON product_tray_batch(batch_id);
