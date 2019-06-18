-- :name upsert-offset! :!
INSERT INTO unilog_offsets (flow_instance, unilog_id)
VALUES (:flow-instance, :unilog-id)
ON CONFLICT (flow_instance)
DO
  UPDATE
    SET unilog_id = :unilog-id,
        updated_at = NOW()

-- :name get-offset :? :1
SELECT unilog_id FROM unilog_offsets WHERE flow_instance = :flow-instance