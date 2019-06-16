-- :name add-message :<! :1
INSERT INTO process_later_messages(unilog_id, flow_instance, message, flow_id, entity_type)
VALUES (:unilog-id, :flow-instance, :message, :flow-id, :entity-type)
RETURNING *

-- :name messages-for-flow-instance :? :*
SELECT * FROM process_later_messages
WHERE flow_instance=:flow-instance
ORDER BY id

-- :name delete-message :! :n
DELETE FROM process_later_messages
WHERE id=:id

-- :name delete-messages-related! :! :n
DELETE FROM process_later_messages
WHERE flow_instance=:flow-instance
AND flow_id=:flow-id
AND entity_type=:entity-type

-- :name delete-messages-related-before! :! :n
DELETE FROM process_later_messages
WHERE flow_instance=:flow-instance
AND flow_id=:flow-id
AND entity_type=:entity-type
AND id <= :id