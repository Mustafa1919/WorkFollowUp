-- ADR-0018 -- kisisel erisim token'lari (PAT), acik API icin. refresh_tokens/verification_tokens
-- (V5) ile AYNI mantik: kullaniciya gore izole, RLS YOK -- kimlik dogrulama verisi workspace'e
-- degil kullaniciya baglidir.
CREATE TABLE personal_access_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users(id),
    name          VARCHAR(100) NOT NULL,
    token_hash    VARCHAR(255) NOT NULL UNIQUE,
    token_preview VARCHAR(20) NOT NULL,
    last_used_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pat_user_id ON personal_access_tokens(user_id);
