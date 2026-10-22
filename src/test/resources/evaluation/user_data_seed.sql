-- USER_DATA 답변 평가용 데이터입니다. UserDataAnswerEvaluation이 테스트 컨테이너 DB에만 넣습니다.
-- 기준일은 2026-10-12입니다. billing_records.billing_month는 청구서가 나온 달, usage_records.billing_month는 사용한 달입니다.

-- ── 사용자 ───────────────────────────────────────────────────────────
INSERT INTO users (email, password_hash, name, birth_date, gender, residence_area, role, created_at)
VALUES ('eval-a@ubot.test', 'not-used', '홍길동', DATE '1998-05-20', 'MALE', '서울 강남구', 'USER', TIMESTAMP '2024-11-03 10:00'),
       ('eval-b@ubot.test', 'not-used', '김유비', DATE '1985-12-01', 'FEMALE', '경기 성남시 분당구', 'USER', TIMESTAMP '2023-06-10 09:00');

-- ── 상품 ─────────────────────────────────────────────────────────────
INSERT INTO plans (plan_code, name, description, network_type, target_group, monthly_fee,
    data_amount_mb, data_unlimited, exhausted_speed_kbps, voice_minutes, voice_unlimited,
    sms_count, sms_unlimited, tethering_amount_mb)
VALUES ('EVAL-5G-STD', '5G 스탠다드', '평가용 5G 요금제', 'FIVE_G', 'GENERAL', 75000,
        153600, FALSE, 5000, NULL, TRUE, NULL, TRUE, 30720),
       ('EVAL-LTE-BASIC', 'LTE 베이직', '평가용 LTE 요금제', 'LTE', 'GENERAL', 33000,
        6144, FALSE, 400, 300, FALSE, 100, FALSE, 0);

INSERT INTO addon_services (code, name, monthly_fee)
VALUES ('EVAL-ADDON-COLOR', 'V컬러링', 3300),
       ('EVAL-ADDON-MUSIC', '지니뮤직', 7900),
       ('EVAL-ADDON-SAFE', '데이터 안심옵션', 2200);

INSERT INTO bundle_products (code, name, discount_amount)
VALUES ('EVAL-BUNDLE-NET', '인터넷 결합', 5500);

INSERT INTO roaming_products (code, name, daily_fee, data_amount_mb, country)
VALUES ('EVAL-ROAMING-JP', '일본 데이터 로밍', 11000, 3072, '일본'),
       ('EVAL-ROAMING-US', '미국 데이터 로밍', 13200, 2048, '미국');

-- ── 사용자 A: 회선 1개 ───────────────────────────────────────────────
INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at,
    sim_type, sim_identifier, sim_status, sim_activated_at)
VALUES ((SELECT user_id FROM users WHERE email = 'eval-a@ubot.test'),
        (SELECT plan_id FROM plans WHERE plan_code = 'EVAL-5G-STD'),
        '010-2468-1357', 'ACTIVE', TIMESTAMP '2025-03-02 11:00',
        'USIM', '8982051234567891357', 'ACTIVE', TIMESTAMP '2025-03-02 11:00');

-- 사용량: 2025-11 ~ 2026-10. 10월은 10-11까지만 집계되어 미확정입니다.
INSERT INTO usage_records (subscription_id, billing_month, data_used_mb, voice_used_minutes, sms_used_count,
    allowance_snapshot, aggregated_through_date, finalized_at, full_month_active)
SELECT s.subscription_id, v.month, v.data_mb, v.voice, v.sms,
       '{"dataAmountMb":153600,"dataUnlimited":false,"voiceUnlimited":true,"smsUnlimited":true}'::jsonb,
       CASE WHEN v.month = DATE '2026-10-01' THEN DATE '2026-10-11'
            ELSE (v.month + INTERVAL '1 month - 1 day')::DATE END,
       CASE WHEN v.month = DATE '2026-10-01' THEN NULL
            ELSE (v.month + INTERVAL '1 month')::TIMESTAMP + INTERVAL '3 hours' END,
       CASE WHEN v.month = DATE '2026-10-01' THEN NULL ELSE TRUE END
FROM subscriptions s,
     (VALUES (DATE '2025-11-01', 41230, 215, 12),
             (DATE '2025-12-01', 52870, 248, 18),
             (DATE '2026-01-01', 38450, 190, 9),
             (DATE '2026-02-01', 35120, 176, 7),
             (DATE '2026-03-01', 153600, 230, 15),
             (DATE '2026-04-01', 47300, 205, 11),
             (DATE '2026-05-01', 61840, 260, 14),
             (DATE '2026-06-01', 58210, 241, 10),
             (DATE '2026-07-01', 72950, 198, 22),
             (DATE '2026-08-01', 66480, 233, 13),
             (DATE '2026-09-01', 54770, 221, 16),
             (DATE '2026-10-01', 18940, 87, 4)) AS v(month, data_mb, voice, sms)
WHERE s.phone_number = '010-2468-1357';

-- 청구서: 2025-11 ~ 2026-10. 기본 61,950원 = 월정액 75,000 + 부가서비스 11,200 - 약정 18,750 - 결합 5,500
-- 2026-03 청구분은 기타 요금 2,200원(데이터 안심옵션 마지막 달), 2026-07 청구분은 6월에 쓴 미국 로밍 7일 92,400원이 있습니다.
-- 2026-10은 미납(기한 전), 2026-08은 연체, 나머지는 납부 완료입니다.
INSERT INTO billing_records (subscription_id, billing_month, base_fee, addon_fee, roaming_fee, extra_fee,
    discount_amount, total_amount, due_date, payment_status, paid_amount, payment_method, paid_at)
SELECT s.subscription_id, v.month, 75000, 11200, v.roaming, v.extra, 24250,
       75000 + 11200 + v.roaming + v.extra - 24250,
       (v.month + INTERVAL '24 days')::DATE, v.status,
       CASE WHEN v.status = 'PAID' THEN 75000 + 11200 + v.roaming + v.extra - 24250 ELSE 0 END,
       CASE WHEN v.status = 'PAID' THEN v.method END,
       CASE WHEN v.status = 'PAID' THEN (v.month + INTERVAL '19 days')::TIMESTAMP + INTERVAL '9 hours' END
FROM subscriptions s,
     (VALUES (DATE '2025-11-01', 0, 0, 'PAID', 'AUTO_TRANSFER'),
             (DATE '2025-12-01', 0, 0, 'PAID', 'AUTO_TRANSFER'),
             (DATE '2026-01-01', 0, 0, 'PAID', 'AUTO_TRANSFER'),
             (DATE '2026-02-01', 0, 0, 'PAID', 'AUTO_TRANSFER'),
             (DATE '2026-03-01', 0, 2200, 'PAID', 'AUTO_TRANSFER'),
             (DATE '2026-04-01', 0, 0, 'PAID', 'AUTO_TRANSFER'),
             (DATE '2026-05-01', 0, 0, 'PAID', 'CARD'),
             (DATE '2026-06-01', 0, 0, 'PAID', 'AUTO_TRANSFER'),
             (DATE '2026-07-01', 92400, 0, 'PAID', 'CARD'),
             (DATE '2026-08-01', 0, 0, 'OVERDUE', NULL),
             (DATE '2026-09-01', 0, 0, 'PAID', 'ACCOUNT'),
             (DATE '2026-10-01', 0, 0, 'UNPAID', NULL)) AS v(month, roaming, extra, status, method)
WHERE s.phone_number = '010-2468-1357';

-- 약정·할부: 선택약정(월 18,750원 할인) + 단말 할부 진행 중
INSERT INTO subscription_contracts (subscription_id, contract_type, started_at, expires_at, discount_amount,
    device_name, device_total_amount, installment_months, paid_installment_months, monthly_installment_amount,
    remaining_installment_amount, installment_started_at, installment_expires_at, installment_status)
SELECT subscription_id, 'SELECTIVE_CONTRACT', DATE '2025-03-02', DATE '2027-03-01', 18750,
       'Galaxy S25', 1155000, 24, 19, 48125, 240625, DATE '2025-03-02', DATE '2027-03-01', 'ACTIVE'
FROM subscriptions WHERE phone_number = '010-2468-1357';

-- 부가서비스: 이용 중 2개, 해지 1개
INSERT INTO subscription_addons (subscription_id, addon_service_id, subscribed_at, canceled_at, status,
    service_name_snapshot, monthly_fee_snapshot)
SELECT s.subscription_id, a.addon_service_id, v.subscribed_at, v.canceled_at, v.status, a.name, a.monthly_fee
FROM subscriptions s
     JOIN (VALUES ('EVAL-ADDON-COLOR', TIMESTAMP '2025-03-02 11:00', NULL::TIMESTAMP, 'ACTIVE'),
                  ('EVAL-ADDON-MUSIC', TIMESTAMP '2025-06-15 20:00', NULL::TIMESTAMP, 'ACTIVE'),
                  ('EVAL-ADDON-SAFE', TIMESTAMP '2025-04-01 09:00', TIMESTAMP '2026-02-28 23:00', 'CANCELED'))
         AS v(code, subscribed_at, canceled_at, status) ON TRUE
     JOIN addon_services a ON a.code = v.code
WHERE s.phone_number = '010-2468-1357';

-- 결합: 이용 중 1개
INSERT INTO subscription_bundles (subscription_id, bundle_product_id, joined_at, status,
    product_name_snapshot, applied_discount_amount)
SELECT s.subscription_id, b.bundle_product_id, TIMESTAMP '2025-03-02 11:00', 'ACTIVE', b.name, b.discount_amount
FROM subscriptions s, bundle_products b
WHERE s.phone_number = '010-2468-1357' AND b.code = 'EVAL-BUNDLE-NET';

-- 로밍: 예약 1건(일본), 만료 1건(미국, 6월 사용 → 2026-07 청구분)
INSERT INTO subscription_roamings (subscription_id, roaming_product_id, start_date, end_date, status,
    product_name_snapshot, daily_fee_snapshot, data_amount_mb_snapshot)
SELECT s.subscription_id, r.roaming_product_id, v.start_date, v.end_date, v.status, r.name, r.daily_fee, r.data_amount_mb
FROM subscriptions s
     JOIN (VALUES ('EVAL-ROAMING-JP', DATE '2026-10-20', DATE '2026-10-24', 'SCHEDULED'),
                  ('EVAL-ROAMING-US', DATE '2026-06-10', DATE '2026-06-16', 'EXPIRED'))
         AS v(code, start_date, end_date, status) ON TRUE
     JOIN roaming_products r ON r.code = v.code
WHERE s.phone_number = '010-2468-1357';

-- ── 사용자 B: 회선 2개 ───────────────────────────────────────────────
INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at)
VALUES ((SELECT user_id FROM users WHERE email = 'eval-b@ubot.test'),
        (SELECT plan_id FROM plans WHERE plan_code = 'EVAL-5G-STD'),
        '010-1234-5678', 'ACTIVE', TIMESTAMP '2024-01-10 14:00'),
       ((SELECT user_id FROM users WHERE email = 'eval-b@ubot.test'),
        (SELECT plan_id FROM plans WHERE plan_code = 'EVAL-LTE-BASIC'),
        '010-9876-0001', 'ACTIVE', TIMESTAMP '2025-09-01 10:00');

INSERT INTO usage_records (subscription_id, billing_month, data_used_mb, voice_used_minutes, sms_used_count,
    allowance_snapshot, aggregated_through_date, finalized_at, full_month_active)
SELECT s.subscription_id, v.month, v.data_mb, v.voice, v.sms, v.allowance::jsonb,
       CASE WHEN v.month = DATE '2026-10-01' THEN DATE '2026-10-11' ELSE DATE '2026-09-30' END,
       CASE WHEN v.month = DATE '2026-10-01' THEN NULL ELSE TIMESTAMP '2026-10-01 03:00' END,
       CASE WHEN v.month = DATE '2026-10-01' THEN NULL ELSE TRUE END
FROM subscriptions s
     JOIN (VALUES ('010-1234-5678', DATE '2026-09-01', 48200, 180, 6,
                   '{"dataAmountMb":153600,"dataUnlimited":false,"voiceUnlimited":true,"smsUnlimited":true}'),
                  ('010-1234-5678', DATE '2026-10-01', 15300, 64, 2,
                   '{"dataAmountMb":153600,"dataUnlimited":false,"voiceUnlimited":true,"smsUnlimited":true}'),
                  ('010-9876-0001', DATE '2026-09-01', 5900, 240, 35,
                   '{"dataAmountMb":6144,"dataUnlimited":false,"voiceMinutes":300,"voiceUnlimited":false,"smsCount":100,"smsUnlimited":false}'),
                  ('010-9876-0001', DATE '2026-10-01', 2100, 95, 12,
                   '{"dataAmountMb":6144,"dataUnlimited":false,"voiceMinutes":300,"voiceUnlimited":false,"smsCount":100,"smsUnlimited":false}'))
         AS v(phone, month, data_mb, voice, sms, allowance) ON s.phone_number = v.phone;

INSERT INTO billing_records (subscription_id, billing_month, base_fee, addon_fee, total_amount, due_date,
    payment_status, paid_amount, payment_method, paid_at)
SELECT s.subscription_id, v.month, v.base_fee, v.addon_fee, v.base_fee + v.addon_fee,
       (v.month + INTERVAL '24 days')::DATE, v.status,
       CASE WHEN v.status = 'PAID' THEN v.base_fee + v.addon_fee ELSE 0 END,
       CASE WHEN v.status = 'PAID' THEN 'AUTO_TRANSFER' END,
       CASE WHEN v.status = 'PAID' THEN (v.month + INTERVAL '19 days')::TIMESTAMP + INTERVAL '9 hours' END
FROM subscriptions s
     JOIN (VALUES ('010-1234-5678', DATE '2026-09-01', 75000, 3300, 'PAID'),
                  ('010-1234-5678', DATE '2026-10-01', 75000, 3300, 'UNPAID'),
                  ('010-9876-0001', DATE '2026-09-01', 33000, 7900, 'PAID'),
                  ('010-9876-0001', DATE '2026-10-01', 33000, 7900, 'UNPAID'))
         AS v(phone, month, base_fee, addon_fee, status) ON s.phone_number = v.phone;

INSERT INTO subscription_addons (subscription_id, addon_service_id, subscribed_at, status,
    service_name_snapshot, monthly_fee_snapshot)
SELECT s.subscription_id, a.addon_service_id, s.activated_at, 'ACTIVE', a.name, a.monthly_fee
FROM subscriptions s
     JOIN (VALUES ('010-1234-5678', 'EVAL-ADDON-COLOR'),
                  ('010-9876-0001', 'EVAL-ADDON-MUSIC')) AS v(phone, code) ON s.phone_number = v.phone
     JOIN addon_services a ON a.code = v.code;
