-- 보관 기간이 지난 데이터를 지우는 프로시저입니다. DataRetentionScheduler가 매일 CALL 합니다.
--
-- 로그는 3개월, FAQ 모니터링에 쓰는 question_log·faq_log는 3년(3개월 뒤 익명화), 구독·청구는 5년 보관합니다.
-- 회원·FAQ·매장·상품·공지는 soft delete 전에는 지우지 않고, soft delete 뒤 기간이 지나면 지웁니다.
--
-- 반복 마이그레이션이라 이 파일 내용이 바뀔 때마다 모든 V 파일 뒤에 다시 실행됩니다.
-- - CREATE OR REPLACE로 작성해 여러 번 실행돼도 안전하게 유지합니다.
-- - 프로시저 인자를 바꿀 때는 이전 시그니처를 DROP PROCEDURE IF EXISTS로 먼저 지웁니다.
--   (인자가 다르면 덮어쓰지 않고 같은 이름의 프로시저가 하나 더 생깁니다.)
-- - PL/pgSQL은 테이블·컬럼을 실행할 때 확인하므로, 이 프로시저가 쓰는 컬럼을 바꾸면
--   DataRetentionPurgeIntegrationTest가 실패하는지 확인합니다.
--
-- 프로시저 안에서 COMMIT 하므로 트랜잭션 밖에서 CALL 해야 합니다.

-- condition에 맞는 행을 batch_size건씩 지우고 매번 커밋합니다. 락이 길게 잡히는 것을 막습니다.
-- target_table, key_column, condition은 이 파일 안의 상수만 넘깁니다.
CREATE OR REPLACE PROCEDURE purge_in_batches(
    target_table TEXT,
    key_column TEXT,
    condition TEXT,
    batch_size INTEGER
)
LANGUAGE plpgsql
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    LOOP
        EXECUTE format(
                'DELETE FROM %1$s WHERE %2$I IN (SELECT %2$I FROM %1$s WHERE %3$s LIMIT %4$s)',
                target_table, key_column, condition, batch_size);
        GET DIAGNOSTICS deleted_count = ROW_COUNT;
        COMMIT;
        EXIT WHEN deleted_count < batch_size;
    END LOOP;
END;
$$;

-- 하위 → 상위 순서로 지웁니다. 상위를 먼저 확인하면 참조가 남아 있어 다음 날로 밀립니다.
CREATE OR REPLACE PROCEDURE purge_expired_data()
LANGUAGE plpgsql
AS $$
DECLARE
    batch_size CONSTANT INTEGER := 1000;
    now_at CONSTANT TIMESTAMP := LOCALTIMESTAMP;
    log_cutoff CONSTANT TIMESTAMP := now_at - INTERVAL '3 months';
    master_cutoff CONSTANT TIMESTAMP := now_at - INTERVAL '3 years';
    monitoring_cutoff CONSTANT TIMESTAMP := now_at - INTERVAL '3 years';
    legal_cutoff CONSTANT DATE := (now_at - INTERVAL '5 years')::DATE;
    updated_count INTEGER;
BEGIN
    -- 1. 만료된 리프레시 토큰
    CALL purge_in_batches('refresh_tokens', 'id',
            format('expired_at < %L', now_at), batch_size);

    -- 2. 3개월이 지난 질문 로그 익명화
    --    FAQ 모니터링은 question_log의 생성 시각과 faq_log만 쓰므로 행은 3년 남기고 원문과 회원·대화 연결만 비웁니다.
    --    진행 중인 대화의 질문은 대화 기록 화면에 쓰이므로 대화가 3개월 비활성일 때만 비웁니다.
    --    다음 단계에서 대화를 지울 때 CASCADE로 함께 지워지지 않도록 대화 삭제보다 먼저 실행합니다.
    LOOP
        UPDATE question_log
        SET user_question = '',
            llm_question = '',
            normalized_question = '',
            sub_question = '',
            user_text = '',
            user_id = NULL,
            conversation_id = NULL,
            anonymized_at = now_at
        WHERE id IN (
            SELECT ql.id
            FROM question_log ql
            LEFT JOIN conversations c ON c.conversation_id = ql.conversation_id
            WHERE ql.anonymized_at IS NULL
              AND ql.created_at < log_cutoff
              AND (ql.conversation_id IS NULL OR c.updated_at < log_cutoff)
            LIMIT batch_size
        );
        GET DIAGNOSTICS updated_count = ROW_COUNT;
        COMMIT;
        EXIT WHEN updated_count < batch_size;
    END LOOP;

    -- 3. 마지막 활동 후 3개월이 지난 대화 (남은 question_log·answer_attempts_history CASCADE)
    --    미답변 질문은 attempt_id만 NULL이 되고 그룹과 함께 5단계에서 지워집니다.
    CALL purge_in_batches('conversations', 'conversation_id',
            format('updated_at < %L', log_cutoff), batch_size);

    -- 4. FAQ 모니터링 보관 기간(3년)이 지난 질문 로그 (faq_log CASCADE)와 대화에 속하지 않은 예전 답변 시도
    CALL purge_in_batches('question_log', 'id',
            format('created_at < %L', monitoring_cutoff), batch_size);
    CALL purge_in_batches('answer_attempts_history', 'attempt_id',
            format('conversation_id IS NULL AND created_at < %L', log_cutoff), batch_size);

    -- 5. 처리가 끝난 미답변 그룹 (질문·임베딩 CASCADE)
    --    PENDING·ON_HOLD는 관리자 검토 대상이라 그룹과 질문을 모두 남깁니다. 질문을 지우면 question_count와 중심 벡터가
    --    다음 질문이 들어올 때 남은 질문만으로 다시 계산돼 관리자 우선순위가 흔들립니다.
    CALL purge_in_batches('unanswered_question_groups', 'id',
            format($c$status IN ('APPROVED', 'REJECTED') AND updated_at < %L$c$, log_cutoff), batch_size);

    CALL purge_in_batches('outbox_events', 'id',
            format('retry_count >= 5 AND next_attempt_at < %L', now_at - INTERVAL '7 days'), batch_size);

    -- 6. 방문일이 3개월 지난 예약 (알림 CASCADE)
    --    예약을 COMPLETED·NO_SHOW로 바꾸는 기능이 없어 지난 예약도 RESERVED로 남으므로 상태와 관계없이 방문일로 판단합니다.
    CALL purge_in_batches('store_reservations', 'reservation_id',
            format('visit_at < %L', log_cutoff), batch_size);

    -- 7. 알림
    CALL purge_in_batches('notifications', 'notification_id',
            format('created_at < %L', log_cutoff), batch_size);

    -- 8-1. 탈퇴 후 3개월이 지났고 구독이 없는 회원 (남은 로그 CASCADE, 감사 컬럼 SET NULL)
    CALL purge_in_batches('users', 'user_id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = users.user_id)
                AND NOT EXISTS (SELECT 1 FROM retention.withdrawn_user_records r WHERE r.user_id = users.user_id)$c$,
                    log_cutoff),
            batch_size);

    -- 8-2. 구독이 있는 탈퇴 회원은 이름·이메일을 분리 보관하고 users의 개인정보 컬럼을 비웁니다.
    --      retain_until은 최소 보관 기한이고, 실제 삭제는 10단계에서 남은 구독이 없을 때 합니다.
    INSERT INTO retention.withdrawn_user_records (user_id, name, email, legal_basis, retain_until)
    SELECT u.user_id,
           u.name,
           u.email,
           'E_COMMERCE_ACT_5Y',
           (GREATEST(u.deleted_at, COALESCE(MAX(s.terminated_at), u.deleted_at)) + INTERVAL '5 years')::DATE
    FROM users u
    JOIN subscriptions s ON s.user_id = u.user_id
    WHERE u.deleted_at < log_cutoff
      AND NOT EXISTS (SELECT 1 FROM retention.withdrawn_user_records r WHERE r.user_id = u.user_id)
    GROUP BY u.user_id;

    UPDATE users u
    SET email = 'deleted-' || u.user_id,
        name = 'deleted-' || u.user_id,
        password_hash = 'deleted',
        birth_date = NULL,
        gender = NULL,
        residence_area = NULL,
        updated_at = now_at
    FROM retention.withdrawn_user_records r
    WHERE r.user_id = u.user_id
      AND u.email <> 'deleted-' || u.user_id;
    COMMIT;

    -- 9. 법정 보존 기간(5년)이 지난 구독·청구 기록
    CALL purge_in_batches('billing_records', 'billing_id',
            format('billing_month < %L', legal_cutoff), batch_size);
    CALL purge_in_batches('usage_records', 'usage_id',
            format('billing_month < %L', legal_cutoff), batch_size);
    CALL purge_in_batches('subscription_contracts', 'contract_id',
            format('COALESCE(canceled_at::DATE, expires_at) < %L', legal_cutoff), batch_size);
    CALL purge_in_batches('subscription_bundles', 'subscription_bundle_id',
            format('ended_at < %L', legal_cutoff), batch_size);
    CALL purge_in_batches('subscription_addons', 'subscription_addon_id',
            format('canceled_at < %L', legal_cutoff), batch_size);
    CALL purge_in_batches('subscription_roamings', 'subscription_roaming_id',
            format('COALESCE(canceled_at::DATE, end_date) < %L', legal_cutoff), batch_size);
    CALL purge_in_batches('subscriptions', 'subscription_id',
            format($c$status = 'TERMINATED' AND terminated_at < %L
                AND NOT EXISTS (SELECT 1 FROM billing_records c WHERE c.subscription_id = subscriptions.subscription_id)
                AND NOT EXISTS (SELECT 1 FROM usage_records c WHERE c.subscription_id = subscriptions.subscription_id)
                AND NOT EXISTS (SELECT 1 FROM subscription_contracts c WHERE c.subscription_id = subscriptions.subscription_id)
                AND NOT EXISTS (SELECT 1 FROM subscription_bundles c WHERE c.subscription_id = subscriptions.subscription_id)
                AND NOT EXISTS (SELECT 1 FROM subscription_addons c WHERE c.subscription_id = subscriptions.subscription_id)
                AND NOT EXISTS (SELECT 1 FROM subscription_roamings c WHERE c.subscription_id = subscriptions.subscription_id)$c$,
                    legal_cutoff),
            batch_size);

    -- 10. 보관 기한이 지났고 남은 구독이 없는 분리 보관 회원
    WITH expired AS (
        DELETE FROM retention.withdrawn_user_records r
        WHERE r.retain_until < now_at::DATE
          AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = r.user_id)
        RETURNING r.user_id
    )
    DELETE FROM users u
    USING expired e
    WHERE u.user_id = e.user_id;
    COMMIT;

    -- 11. soft delete 후 3년이 지났고 참조가 없는 마스터 (FK 기본값이 참조가 남은 행의 삭제를 막습니다)
    CALL purge_in_batches('faq', 'id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM faq_log fl WHERE fl.faq_id = faq.id)
                AND NOT EXISTS (SELECT 1 FROM unanswered_questions uq WHERE uq.best_faq_id = faq.id)
                AND NOT EXISTS (SELECT 1 FROM unanswered_question_groups g
                                WHERE g.related_faq_id = faq.id OR g.resolved_faq_id = faq.id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('faq_category', 'id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM faq f WHERE f.category_id = faq_category.id)
                AND NOT EXISTS (SELECT 1 FROM old_faq o WHERE o.category_id = faq_category.id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('stores', 'store_id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM store_reservations sr WHERE sr.store_id = stores.store_id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('service_types', 'service_type_id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM store_services ss WHERE ss.service_type_id = service_types.service_type_id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('plans', 'plan_id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.plan_id = plans.plan_id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('bundle_products', 'bundle_product_id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM subscription_bundles sb WHERE sb.bundle_product_id = bundle_products.bundle_product_id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('addon_services', 'addon_service_id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM subscription_addons sa WHERE sa.addon_service_id = addon_services.addon_service_id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('roaming_products', 'roaming_product_id',
            format($c$deleted_at < %L
                AND NOT EXISTS (SELECT 1 FROM subscription_roamings sr WHERE sr.roaming_product_id = roaming_products.roaming_product_id)$c$,
                    master_cutoff),
            batch_size);
    CALL purge_in_batches('notices', 'notice_id',
            format('deleted_at < %L', master_cutoff), batch_size);
END;
$$;
