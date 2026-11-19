CREATE TABLE coupon (
    code varchar(64) PRIMARY KEY CHECK (code ~ '^[A-Z0-9_-]+$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    country char(2) NOT NULL,
    max_uses integer NOT NULL CHECK (max_uses > 0),
    uses integer NOT NULL DEFAULT 0 CHECK (uses BETWEEN 0 AND max_uses)
);

CREATE TABLE redemption (
    code varchar(64) REFERENCES coupon,
    user_id varchar(128) NOT NULL,
    PRIMARY KEY (code, user_id)
);
