-- Faz 3 / Dilim 3.0: sprint yasam dongusu.
-- started_at / completed_at: velocity'nin degismezligi icin "sprint kapandigi ANDAKI" kesit
-- zamani (PHASE_3_DETAILED_DESIGN.md Bolum 1.1). Analitik worker, sprint uyeligini ve story
-- point'i task_events tarihcesinden BU zamana gore yeniden kurar; sonradan yapilan tasimalar
-- gecmis sprint'in metrigini degistiremez.
ALTER TABLE sprints
    ADD COLUMN started_at   TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ;

ALTER TABLE sprints
    ADD CONSTRAINT chk_sprints_status CHECK (status IN ('planned', 'active', 'completed'));
