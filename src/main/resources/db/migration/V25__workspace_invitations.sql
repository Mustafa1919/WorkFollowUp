-- Urunlestirme Dalga 1.4: token'li davet.
--
-- `AddWorkspaceMemberRequest`/POST /api/v1/workspaces/members (degismedi, hala mevcut) yalniz
-- ONCEDEN kayitli bir kullaniciyi aninda ekler. Bu tablo, e-posta altyapisi artik hazir oldugu
-- icin (V24) KAYITSIZ e-postalari da davet edebilen token tabanli bir akis ekliyor.
--
-- verification_tokens (V5) ile AYNI desen: RLS UYGULANMAZ. Davet kabul ucu (token'i cozup
-- workspace'i bulma) workspace secilmeden/tenant context kurulmadan ONCE calismak zorunda —
-- workspace_users/users/verification_tokens ile ayni "kimlik-oncesi" katman.
CREATE TABLE workspace_invitations (
    id           UUID PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces(id),
    email        VARCHAR(255) NOT NULL,
    role         VARCHAR(50)  NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    invited_by   UUID         NOT NULL REFERENCES users(id),
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    expires_at   TIMESTAMPTZ  NOT NULL,
    accepted_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_workspace_invitations_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED'))
);

-- "EXPIRED" DB'de ayri bir durum DEGIL (status hala PENDING kalir); expires_at < now() olarak
-- servis katmaninda TURETILIR (goals.sql'deki manual_value CHECK'i gibi, ayri bir job/state
-- makinesi gerekmesin diye bilerek basit tutuldu).

-- ADMIN'in workspace'in bekleyen davetlerini listelemesi icin.
CREATE INDEX idx_workspace_invitations_workspace_pending
    ON workspace_invitations (workspace_id, created_at DESC)
    WHERE status = 'PENDING';

-- Ayni e-postaya yeniden davet gonderilirken ("resend") ONCEKI PENDING davetin bulunup
-- REVOKED'e cekilmesi icin (bkz. WorkspaceInvitationService#invite).
CREATE INDEX idx_workspace_invitations_workspace_email_pending
    ON workspace_invitations (workspace_id, email)
    WHERE status = 'PENDING';
