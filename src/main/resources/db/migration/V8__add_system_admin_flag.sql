-- SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.2 — "Global Roller: SYSTEM_ADMIN (Sistemin sahibi)"
-- tasarimda vardi ama Faz1'de hic implemente edilmemisti (JWT'ye hep sabit "USER" rolu
-- yaziliyordu). PHASE_2_DETAILED_DESIGN.md Bolum 3.3 madde 3'teki DLT replay endpoint'i workspace'e
-- ozgu degil, TUM tenant'lari etkileyebilen sistem-geneli bir arac oldugu icin bu eksik parca
-- burada tamamlaniyor.

ALTER TABLE users ADD COLUMN system_admin BOOLEAN NOT NULL DEFAULT false;
