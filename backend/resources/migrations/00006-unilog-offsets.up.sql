--;;
CREATE TABLE unilog_offsets (
    db_name varchar(40) UNIQUE NOT NULL,
    unilog_id bigint NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
