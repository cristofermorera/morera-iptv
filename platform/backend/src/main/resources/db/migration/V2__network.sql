CREATE TABLE network_preference (
 account_id uuid PRIMARY KEY REFERENCES account(id) ON DELETE CASCADE,
 dns varchar(20) NOT NULL, connection varchar(20) NOT NULL
);
