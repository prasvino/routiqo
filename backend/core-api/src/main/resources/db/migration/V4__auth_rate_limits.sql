CREATE TABLE auth_rate_bucket (
    key_hash CHAR(64) NOT NULL,
    window_start BIGINT NOT NULL,
    hits INTEGER NOT NULL CHECK (hits > 0),
    PRIMARY KEY (key_hash, window_start)
);
CREATE INDEX auth_rate_window ON auth_rate_bucket(window_start);
