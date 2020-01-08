--;;
CREATE INDEX user_node_role_user_id ON user_node_role (user_id);
CREATE INDEX users_flow_ids_user_id_admin ON users_flow_ids (user_id, super_admin);
CREATE INDEX nodes_type_instance ON nodes (type, flow_instance);
