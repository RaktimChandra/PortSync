-- Fixed terminal layout: 3 berths, 6 quay cranes, 4 blocks x 6 rows x 10 bays x 4 tiers.
-- Must match dev.portsync.events.TerminalLayout.

INSERT INTO berths (id) VALUES ('B1'), ('B2'), ('B3');

INSERT INTO cranes (id) VALUES ('QC01'), ('QC02'), ('QC03'), ('QC04'), ('QC05'), ('QC06');

INSERT INTO yard_slots (block, row_no, bay_no, tier)
SELECT b.block, r.row_no, y.bay_no, t.tier
FROM (VALUES ('A'), ('B'), ('C'), ('D')) AS b (block)
CROSS JOIN generate_series(1, 6)  AS r (row_no)
CROSS JOIN generate_series(1, 10) AS y (bay_no)
CROSS JOIN generate_series(1, 4)  AS t (tier);
