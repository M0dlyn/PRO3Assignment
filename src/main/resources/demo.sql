INSERT INTO animal (registration_number, species, arrival_weight_kg, arrival_date, origin) VALUES
 ('A001', 'pig', 120.000, DATE '2026-10-01', 'North Farm'),
 ('A002', 'pig', 125.000, DATE '2026-10-01', 'South Farm'),
 ('A003', 'pig', 118.000, DATE '2026-10-02', 'North Farm'),
 ('A004', 'pig', 130.000, DATE '2026-10-03', 'West Farm');
INSERT INTO part_type VALUES ('leg'), ('loin');
INSERT INTO tray VALUES ('T001', 40.000), ('T002', 30.000);
INSERT INTO tray_batch VALUES
 ('B001', 'T001', 'leg', TIMESTAMP '2026-10-01 08:00:00'),
 ('B002', 'T002', 'loin', TIMESTAMP '2026-10-01 08:00:00'),
 ('B003', 'T001', 'leg', TIMESTAMP '2026-10-02 08:00:00');
INSERT INTO part VALUES
 ('CUT001', 'A001', 'B001', 'leg', 8.000),
 ('CUT002', 'A002', 'B001', 'leg', 9.000),
 ('CUT003', 'A001', 'B001', 'leg', 8.000),
 ('CUT004', 'A001', 'B002', 'loin', 5.000),
 ('CUT005', 'A003', 'B003', 'leg', 8.000),
 ('CUT006', 'A003', 'B003', 'leg', 8.000);
INSERT INTO product_type VALUES ('mixed-pack'), ('leg-pack');
INSERT INTO product_requirement VALUES ('mixed-pack', 'leg', 1), ('mixed-pack', 'loin', 1), ('leg-pack', 'leg', 2);
INSERT INTO product VALUES
 ('P001', 'mixed-pack', DATE '2026-10-01'),
 ('P002', 'leg-pack', DATE '2026-10-01'),
 ('P003', 'leg-pack', DATE '2026-10-02');
INSERT INTO product_tray_batch VALUES ('P001', 'B001'), ('P001', 'B002'), ('P002', 'B001'), ('P003', 'B003');
