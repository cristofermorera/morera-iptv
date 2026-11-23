CREATE TABLE sports_channel_alias (
    alias varchar(140) PRIMARY KEY,
    broadcaster varchar(140) NOT NULL
);
CREATE TABLE sports_channel_override (
    event_id varchar(260) NOT NULL,
    channel_id uuid NOT NULL REFERENCES channel(id) ON DELETE CASCADE,
    allowed boolean NOT NULL,
    PRIMARY KEY (event_id, channel_id)
);
