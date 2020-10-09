--;;
ALTER TABLE nodes DROP COLUMN IF EXISTS is_public;
DELETE from unilog_offsets;