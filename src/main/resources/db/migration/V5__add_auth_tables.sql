-- SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1/1.1.1/1.4 referans alinarak olusturulmustur.
-- Bu tablolarin hicbirine RLS uygulanmaz: kullanici/kimlik dogrulama verisi workspace'e gore
-- degil kullaniciya gore izole edilir (workspace_users ile ayni mantik).

ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;

-- Refresh Token Rotation + Reuse Detection (Bolum 1.1.1): her login bir family_id uretir,
-- rotation ile uretilen tum token'lar aynı aileye baglanir. used_at dolu bir token tekrar
-- gelirse tum aile iptal edilir.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    family_id   UUID NOT NULL,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    access_jti  VARCHAR(64),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- Parola sifirlama VE e-posta dogrulama aynı mekanizmayi paylasir (Bolum 1.4.3):
-- duz token asla saklanmaz, yalniz hash'i.
CREATE TABLE verification_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    purpose     VARCHAR(30) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_verification_tokens_user_id ON verification_tokens(user_id);
