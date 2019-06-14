-- :name get-node-by-flow-id :? :1
SELECT * FROM nodes
WHERE flow_id = :flow-id
      and flow_instance = :flow-instance

-- :name insert-node! :!
INSERT INTO nodes (id, name, type, flow_id, flow_instance, flow_parent_id, is_public, full_path)
VALUES (:id, :name, :type, :flow-id, :flow-instance, :flow-parent-id, :is-public, :full-path)

-- :name insert-root-node! :<! :1
INSERT INTO nodes (id, name, type, flow_id, flow_instance, flow_parent_id, is_public, full_path)
VALUES (:id, :name, :type, :flow-id, :flow-instance, :flow-parent-id, :is-public, :full-path)
ON CONFLICT ON CONSTRAINT n_flow_full_id DO NOTHING
RETURNING id

-- :name next-node-id-seq :? :1
select NEXTVAL('node_id_seq')

-- :name delete-node-by-flow-id! :<! :1
DELETE from nodes
WHERE flow_id = :flow-id
      AND flow_instance = :flow-instance
RETURNING *

-- :name delete-all-childs! :<! :*
DELETE from nodes
WHERE full_path <@ :full-path
RETURNING *