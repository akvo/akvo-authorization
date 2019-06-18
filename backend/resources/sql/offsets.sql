-- :name upsert-offset! :!
INSERT INTO unilog_offsets (db_name, unilog_id)
VALUES (:db-name, :unilog-id)
ON CONFLICT (db_name)
DO
  UPDATE
    SET unilog_id = :unilog-id,
        updated_at = NOW()

-- :name get-offset :? :1
SELECT unilog_id FROM unilog_offsets WHERE db_name = :db-name