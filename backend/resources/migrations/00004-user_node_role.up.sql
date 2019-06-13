--;;
CREATE TABLE user_node_role (
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE NOT NULL,
  role_id INTEGER REFERENCES roles(id) ON DELETE CASCADE NOT NULL,
  node_id INTEGER REFERENCES nodes(id) ON DELETE CASCADE NOT NULL,
  flow_id bigint NOT NULL,
  flow_instance varchar(40) NOT NULL,
  constraint unr_flow_full_id unique (flow_instance, flow_id)
);
