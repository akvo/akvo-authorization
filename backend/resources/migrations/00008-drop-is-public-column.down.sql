--;;
ALTER TABLE nodes ADD COLUMN is_public BOOLEAN;
DELETE from unilog_offsets;