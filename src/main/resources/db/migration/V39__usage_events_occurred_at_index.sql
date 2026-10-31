-- SEC-10: gecelik saklama suresi silmesi (occurred_at < esik) icin.
CREATE INDEX IF NOT EXISTS idx_usage_events_occurred_at ON usage_events (occurred_at);
