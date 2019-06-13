--;;
CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email varchar(200) UNIQUE NOT NULL
);
--;;
CREATE TABLE users_flow_ids (
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE NOT NULL,
  flow_id bigint NOT NULL,
  flow_instance varchar(40) NOT NULL,
  email varchar(200),
  --user_name varchar(200),
  --language varchar(200),
  permission_list varchar(5),
  super_admin BOOLEAN,
  constraint u_flow_full_id unique (flow_instance, flow_id)
);
