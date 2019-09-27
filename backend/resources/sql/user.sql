-- :name upsert-user! :<! :1
INSERT INTO users (email)
VALUES
 (
 :email
 )
ON CONFLICT (email)
DO
 UPDATE
   SET email = :email
RETURNING id

-- :name get-user-by-email :? :1
SELECT * from users
WHERE email = :email

-- :name get-flow-user-by-email :? :1
SELECT * from users_flow_ids
WHERE flow_id = :flow-id AND flow_instance = :flow-instance

-- :name upsert-user-flow-id! :i :n
INSERT INTO users_flow_ids (user_id, email, flow_instance, flow_id, super_admin, permission_list)
VALUES (:user-id, :email, :flow-instance, :flow-id, :super-admin, :permission-list)
ON CONFLICT ON CONSTRAINT u_flow_full_id
DO
 UPDATE
   SET user_id = :user-id,
       email = :email,
       super_admin = :super-admin,
       permission_list = :permission-list
RETURNING *

-- :name get-user-by-flow-id :? :1
SELECT * FROM users_flow_ids
WHERE flow_id = :flow-id
      AND flow_instance = :flow-instance

-- :name delete-user-by-flow-id! :<! :1
DELETE from users_flow_ids
WHERE flow_id = :flow-id
      AND flow_instance = :flow-instance
RETURNING user_id

-- :name upsert-user-auth! :!
INSERT INTO user_node_role (flow_instance, flow_id, node_id, user_id, role_id)
VALUES (:flow-instance, :flow-id, :node-id, :user-id, :role-id)
ON CONFLICT ON CONSTRAINT unr_flow_full_id
DO
  UPDATE
    SET node_id = :node-id,
        user_id = :user-id,
        role_id = :role-id
RETURNING *

-- :name change-auths-owner! :!
UPDATE user_node_role
SET user_id = :new-user-id
WHERE user_id = :previous-user-id
    AND flow_instance = :flow-instance

-- :name delete-user-auth! :!
DELETE from user_node_role
WHERE user_id = :user-id
      AND flow_instance = :flow-instance

-- :name delete-user-auth-by-flow-id! :!
DELETE from user_node_role
WHERE flow_id = :flow-id
      AND flow_instance = :flow-instance

-- :name upsert-role! :<! :1
INSERT INTO roles (flow_instance, flow_id, name)
VALUES
 (
 :flow-instance,
 :flow-id,
 :name
 )
ON CONFLICT ON CONSTRAINT r_flow_full_id
DO
 UPDATE
   SET name = :name
RETURNING id

-- :name create-role-perms! :! :n
insert into role_perms (role, perm)
values :tuple*:permissions

-- :name delete-role-perms-for-role! :! :n
DELETE FROM role_perms WHERE role = :id

-- :name get-role-by-flow-id :? :1
-- :doc retrieves a user record given the id
SELECT * FROM roles
WHERE flow_id = :flow-id
    AND flow_instance = :flow-instance

-- :name delete-role-by-flow-id! :!
DELETE from roles
WHERE flow_id = :flow-id
      AND flow_instance = :flow-instance
