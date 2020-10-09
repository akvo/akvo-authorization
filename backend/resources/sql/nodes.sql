-- :name get-node-by-flow-id :? :1
SELECT * FROM nodes
WHERE flow_id = :flow-id
      and flow_instance = :flow-instance

-- :name insert-node! :!
INSERT INTO nodes (id, name, type, flow_id, flow_instance, flow_parent_id, full_path)
VALUES (:id, :name, :type, :flow-id, :flow-instance, :flow-parent-id, :full-path)

-- :name update-node! :!
UPDATE nodes
SET name = :name,
    type = :type,
    flow_parent_id = :flow-parent-id,
    full_path = :full-path
WHERE id=:id;

-- :name update-all-childs-paths! :! :n
UPDATE nodes
  SET full_path = :new-full-path || subpath(full_path, nlevel(:old-full-path))
  WHERE full_path <@ :old-full-path

-- :name insert-root-node! :<! :1
INSERT INTO nodes (id, name, type, flow_id, flow_instance, flow_parent_id, full_path)
VALUES (:id, :name, :type, :flow-id, :flow-instance, :flow-parent-id, :full-path)
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