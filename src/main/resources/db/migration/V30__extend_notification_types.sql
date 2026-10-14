ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;

ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'RESERVATION_CONFIRMED', 'RESERVATION_CANCELED', 'RESERVATION_REMINDER',
    'BILLING_DUE_SOON', 'BILLING_OVERDUE',
    'ROAMING_START', 'ROAMING_END',
    'CONTRACT_EXPIRING', 'INSTALLMENT_EXPIRING',
    'DATA_USAGE_THRESHOLD'
));

ALTER TABLE notifications ADD COLUMN dedupe_key VARCHAR(100);

CREATE UNIQUE INDEX uq_notifications_dedupe_key ON notifications (dedupe_key);

CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
