ALTER TABLE refresh_tokens
    ADD COLUMN previous_token VARCHAR(64),
    ADD COLUMN rotated_at     TIMESTAMP;

UPDATE refresh_tokens
SET token = encode(sha256(convert_to(token, 'UTF8')), 'hex');

CREATE INDEX idx_refresh_tokens_previous_token ON refresh_tokens (previous_token);
