-- 상품(요금제·부가서비스·결합·로밍) 정보 조회 의도를 추가합니다. V13·V20의 인라인 CHECK는 PostgreSQL 기본 이름으로 만들어졌습니다.
ALTER TABLE faq DROP CONSTRAINT faq_intent_check;
ALTER TABLE faq ADD CONSTRAINT faq_intent_check
    CHECK (intent IN ('GENERAL', 'STORE_DATA', 'USER_DATA', 'PRODUCT_DATA'));

ALTER TABLE old_faq DROP CONSTRAINT old_faq_intent_check;
ALTER TABLE old_faq ADD CONSTRAINT old_faq_intent_check
    CHECK (intent IN ('GENERAL', 'STORE_DATA', 'USER_DATA', 'PRODUCT_DATA'));

ALTER TABLE answer_attempts_history DROP CONSTRAINT answer_attempts_history_intent_check;
ALTER TABLE answer_attempts_history ADD CONSTRAINT answer_attempts_history_intent_check
    CHECK (intent IN ('GENERAL', 'STORE_DATA', 'USER_DATA', 'PRODUCT_DATA'));
