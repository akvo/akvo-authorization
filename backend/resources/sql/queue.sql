-- :name add-message :<! :1
INSERT INTO process_later_messages(unilog_id, flow_instance, message)
VALUES (:unilog-id, :flow-instance, :message)
RETURNING *

-- :name messages-for-flow-instance :? :*
SELECT * FROM process_later_messages
WHERE flow_instance=:flow-instance
ORDER BY id

-- :name delete-message :! :n
DELETE FROM process_later_messages
WHERE id=:id