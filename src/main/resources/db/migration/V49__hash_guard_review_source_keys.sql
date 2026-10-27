-- 기존 중복 판정·성공 지문 연결은 유지하고 source_key의 식별자 원값만 제거합니다.
-- 실행별 스냅샷·발견 기록에 같은 변환을 적용합니다. 이미 해시된 키는 다시 해시하지 않습니다.
UPDATE guard_review_items
SET source_key = 'sha256:' || encode(sha256(convert_to(source_key, 'UTF8')), 'hex')
WHERE source_key !~ '^sha256:[0-9a-f]{64}$';

UPDATE guard_review_findings
SET source_key = 'sha256:' || encode(sha256(convert_to(source_key, 'UTF8')), 'hex')
WHERE source_key !~ '^sha256:[0-9a-f]{64}$';

ALTER TABLE guard_review_items
    ADD CONSTRAINT ck_guard_review_items_source_key_hash CHECK (source_key ~ '^sha256:[0-9a-f]{64}$');
ALTER TABLE guard_review_findings
    ADD CONSTRAINT ck_guard_review_findings_source_key_hash CHECK (source_key ~ '^sha256:[0-9a-f]{64}$');
