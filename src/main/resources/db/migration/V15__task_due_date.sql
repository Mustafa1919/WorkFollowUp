-- Frontend takvim gorunumu: gorevin planlandigi gun. Takvim gunu oldugu icin DATE (saat dilimsiz);
-- NULL = tarihsiz (yalniz Kanban'da gorunur). Degisiklikler task_events'e 'due_date_changed'
-- olarak yazilir (TaskEventRepository kurali: gorev uzerindeki her anlamli degisiklik tarihceye).
ALTER TABLE tasks ADD COLUMN due_date DATE;

-- Takvim sorgusu: proje + tarih araligi. Tarihsiz gorevler indekse girmez.
CREATE INDEX idx_tasks_project_due_date ON tasks (project_id, due_date) WHERE due_date IS NOT NULL;
