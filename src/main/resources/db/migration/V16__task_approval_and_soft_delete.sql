-- Tamamlanan gorevin onayi: Done + approved_at dolu = "arsivlendi" (Kanban'dan kalkar, Tamamlananlar
-- sayfasinda listelenir). Durum 'Done' olarak KALIR: analitik (Cycle Time/Velocity/Throughput) onaydan
-- etkilenmez, yeni bir durum eklemek tum durum makinesini ve worker'lari degistirirdi.
ALTER TABLE tasks ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN approved_by UUID REFERENCES users(id);

-- Silme SOFT: task_events (partitioned, append-only) tasks(id)'ye FK ile bagli; fiziksel silme ya
-- tarihceyi silmeyi ya da FK'yi kaldirmayi gerektirirdi. Silinen satir entity seviyesinde
-- (@SQLRestriction) tum sorgulardan dusurulur.
ALTER TABLE tasks ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN deleted_by UUID REFERENCES users(id);

ALTER TABLE tasks ADD CONSTRAINT chk_tasks_approved_is_done
    CHECK (approved_at IS NULL OR status = 'Done');

-- Tamamlananlar sayfasi: proje + onay zamani (yeni -> eski).
CREATE INDEX idx_tasks_project_approved_at ON tasks (project_id, approved_at DESC, id DESC)
    WHERE approved_at IS NOT NULL AND deleted_at IS NULL;
