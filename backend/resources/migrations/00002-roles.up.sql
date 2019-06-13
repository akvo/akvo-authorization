--;;
CREATE TABLE roles (
  id SERIAL PRIMARY KEY,
  flow_id bigint NOT NULL,
  flow_instance varchar(40) NOT NULL,
  name varchar(200),
  constraint r_flow_full_id unique (flow_instance, flow_id)
  );
--;;
CREATE TABLE role_perms (
  role INTEGER REFERENCES roles(id) ON DELETE CASCADE NOT NULL,
  perm varchar(30) NOT NULL);
