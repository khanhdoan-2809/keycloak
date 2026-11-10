CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    identity_issuer VARCHAR(512) NOT NULL,
    identity_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_app_user_identity
        UNIQUE (identity_issuer, identity_subject)
);

CREATE TABLE app_order (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    idempotency_key UUID NOT NULL,

    product VARCHAR(120) NOT NULL,
    quantity INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_order_product
        CHECK (length(btrim(product)) BETWEEN 1 AND 120),

    CONSTRAINT ck_order_quantity
        CHECK (quantity BETWEEN 1 AND 100),

    CONSTRAINT uq_order_idempotency
        UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_order_owner_created
    ON app_order (owner_id, created_at DESC, id DESC);