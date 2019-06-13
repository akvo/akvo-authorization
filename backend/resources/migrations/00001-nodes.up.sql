CREATE TABLE nodes (
  id bigint,
  name varchar(2000),
  is_public BOOLEAN,
  type varchar(20) NOT NULL,
  flow_instance varchar(40) NOT NULL,
  flow_id bigint NOT NULL,
  flow_parent_id bigint,
  full_path ltree NOT NULL,
  constraint n_flow_full_id unique (flow_instance, flow_id));
--;;
CREATE INDEX nodes_parent_path_idx ON nodes USING GIST (full_path);
--;;
CREATE SEQUENCE node_id_seq;
--;;
ALTER TABLE ONLY nodes ALTER COLUMN id SET DEFAULT nextval('node_id_seq'::regclass);
--;;
ALTER TABLE ONLY nodes
    ADD CONSTRAINT nodes_pkey PRIMARY KEY (id);
--;;
ALTER SEQUENCE node_id_seq OWNED BY nodes.id;