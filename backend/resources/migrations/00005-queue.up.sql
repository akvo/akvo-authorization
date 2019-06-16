--;;
CREATE TABLE process_later_messages (
    id SERIAL PRIMARY KEY,
    unilog_id bigint NOT NULL,
    flow_instance varchar(40) NOT NULL,
    flow_id bigint NOT NULL,
    entity_type varchar(40) NOT NULL,
    message BYTEA
);
