-- 로컬 초기화용 사용자 10명과 가입 상품·이용 기록. reset-local-db.ps1이 seed-demo-users.sql 다음에 실행한다.
-- 상품은 V43 카탈로그를 코드로 찾는다. 비밀번호는 기본 계정과 같은 12345678이다.
-- user@user.com은 seed-baseline.sql이 만들고, user2@user.com ~ user10@user.com을 여기서 만든다.
-- 날짜는 실행일 기준이다. usage_records.billing_month는 사용한 달, billing_records.billing_month는 청구서가 나온 달
-- (금액은 전월 사용분)이다.
-- 마지막에 가상 사용자 300명(seed-demo-users.sql)의 청구 월을 같은 기준으로 옮기고, 결합 기록을 그룹으로 묶는다.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (email, password_hash, name, birth_date, gender, residence_area, "role") VALUES
    ('user2@user.com', crypt('12345678', gen_salt('bf')), '김민준', DATE '1990-05-12', 'MALE', '서울특별시 강남구', 'USER'),
    ('user3@user.com', crypt('12345678', gen_salt('bf')), '이서연', DATE '1993-08-21', 'FEMALE', '서울특별시 강남구', 'USER'),
    ('user4@user.com', crypt('12345678', gen_salt('bf')), '박영자', DATE '1956-03-03', 'FEMALE', '경기도 성남시', 'USER'),
    ('user5@user.com', crypt('12345678', gen_salt('bf')), '최지우', DATE '2000-11-02', 'FEMALE', '부산광역시 해운대구', 'USER'),
    ('user6@user.com', crypt('12345678', gen_salt('bf')), '정하준', DATE '2002-01-15', 'MALE', '대구광역시 수성구', 'USER'),
    ('user7@user.com', crypt('12345678', gen_salt('bf')), '강도윤', DATE '1985-07-07', 'MALE', '인천광역시 연수구', 'USER'),
    ('user8@user.com', crypt('12345678', gen_salt('bf')), '윤서아', DATE '1988-09-30', 'FEMALE', '광주광역시 서구', 'USER'),
    ('user9@user.com', crypt('12345678', gen_salt('bf')), '임지호', DATE '1979-12-24', 'MALE', '대전광역시 유성구', 'USER'),
    ('user10@user.com', crypt('12345678', gen_salt('bf')), '한유나', DATE '1996-04-18', 'FEMALE', '울산광역시 남구', 'USER');

-- 회선: 사용자마다 휴대폰 1회선, user7은 태블릿, user8은 워치 회선을 하나 더 쓴다.
CREATE TEMP TABLE seed_line (line_key TEXT, email TEXT, plan_code TEXT, phone_number TEXT,
    months_ago INT, plan_changed_days_ago INT) ON COMMIT DROP;
INSERT INTO seed_line VALUES
    ('U1', 'user@user.com', 'UBOT_BALANCE_05', '010-1000-0001', 30, NULL),
    ('U2', 'user2@user.com', 'UBOT_BALANCE_07', '010-1000-0002', 26, NULL),
    ('U3', 'user3@user.com', 'UBOT_BASIC_07', '010-1000-0003', 20, 40),
    ('U4', 'user4@user.com', 'UBOT_SENIOR_04', '010-1000-0004', 18, NULL),
    ('U5', 'user5@user.com', 'UBOT_YOUTH_04', '010-1000-0005', 14, NULL),
    ('U6', 'user6@user.com', 'UBOT_YOUTH_03', '010-1000-0006', 12, NULL),
    ('U7', 'user7@user.com', 'UBOT_DIRECT_04', '010-1000-0007', 24, NULL),
    ('U7T', 'user7@user.com', 'UBOT_TABLET_02', '010-1000-0071', 10, NULL),
    ('U8', 'user8@user.com', 'UBOT_FLEX_04', '010-1000-0008', 16, NULL),
    ('U8W', 'user8@user.com', 'UBOT_WATCH_02', '010-1000-0081', 8, NULL),
    ('U9', 'user9@user.com', 'UBOT_LIFE_01', '010-1000-0009', 36, NULL),
    ('U10', 'user10@user.com', 'UBOT_BOOK_03', '010-1000-0010', 22, NULL);

INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at, plan_changed_at)
SELECT u.user_id, p.plan_id, l.phone_number, 'ACTIVE',
       date_trunc('day', now()) - make_interval(months => l.months_ago),
       CASE WHEN l.plan_changed_days_ago IS NULL THEN NULL
            ELSE date_trunc('day', now()) - make_interval(days => l.plan_changed_days_ago) END
FROM seed_line l
JOIN users u ON u.email = l.email AND u.deleted_at IS NULL
JOIN plans p ON p.plan_code = l.plan_code;

CREATE TEMP TABLE seed_line_id ON COMMIT DROP AS
SELECT l.line_key, s.subscription_id, s.activated_at
FROM seed_line l
JOIN subscriptions s ON s.phone_number = l.phone_number AND s.status <> 'TERMINATED';

-- 부가서비스 (요금제 포함 혜택은 파일 끝에서 PLAN_INCLUDED 0원으로 넣는다)
CREATE TEMP TABLE seed_addon (line_key TEXT, addon_code TEXT, days_ago INT) ON COMMIT DROP;
INSERT INTO seed_addon VALUES
    ('U1', 'UBOT_ADDON_SPAM', 300), ('U1', 'UBOT_ADDON_DATA_5', 20),
    ('U2', 'UBOT_ADDON_MISSED', 400), ('U2', 'UBOT_ADDON_PHISHING', 60),
    ('U3', 'UBOT_ADDON_MUSIC', 90),
    ('U4', 'UBOT_ADDON_FAMILY', 120),
    ('U5', 'UBOT_ADDON_VIDEO', 45),
    ('U6', 'UBOT_ADDON_DATA_1', 10),
    ('U7', 'UBOT_ADDON_DUAL', 200),
    ('U8', 'UBOT_ADDON_FORWARD', 150),
    ('U9', 'UBOT_ADDON_SPAM', 500), ('U9', 'UBOT_ADDON_VOICE_100', 30),
    ('U10', 'UBOT_ADDON_RING', 100), ('U10', 'UBOT_ADDON_PAY_BLOCK', 250);

INSERT INTO subscription_addons (subscription_id, addon_service_id, subscribed_at, status,
    service_name_snapshot, monthly_fee_snapshot)
SELECT li.subscription_id, a.addon_service_id,
       date_trunc('day', now()) - make_interval(days => sa.days_ago), 'ACTIVE', a.name, a.monthly_fee
FROM seed_addon sa
JOIN seed_line_id li ON li.line_key = sa.line_key
JOIN addon_services a ON a.code = sa.addon_code;

-- 인터넷/IPTV
CREATE TEMP TABLE seed_home (email TEXT, home_code TEXT, months_ago INT) ON COMMIT DROP;
INSERT INTO seed_home VALUES
    ('user@user.com', 'UBOT_HOME_INTERNET_100', 24), ('user@user.com', 'UBOT_HOME_IPTV_BASIC', 24),
    ('user7@user.com', 'UBOT_HOME_INTERNET_100', 12),
    ('user8@user.com', 'UBOT_HOME_IPTV_PREMIUM', 6);

INSERT INTO home_subscriptions (user_id, home_product_id, product_type, status, joined_at,
    product_name_snapshot, monthly_fee_snapshot)
SELECT u.user_id, h.home_product_id, h.product_type, 'ACTIVE',
       date_trunc('day', now()) - make_interval(months => sh.months_ago), h.name, h.monthly_fee
FROM seed_home sh
JOIN users u ON u.email = sh.email AND u.deleted_at IS NULL
JOIN home_products h ON h.code = sh.home_code;

-- 결합: 그룹마다 대표 회선 1개. 할인액은 대표 회선에만 적고 구성 회선은 0원이다.
CREATE TEMP TABLE seed_bundle (group_key TEXT, line_key TEXT, bundle_code TEXT, representative BOOLEAN,
    months_ago INT) ON COMMIT DROP;
INSERT INTO seed_bundle VALUES
    ('HOME', 'U1', 'UBOT_BUNDLE_HOME_100', TRUE, 12),
    ('FAMILY', 'U2', 'UBOT_BUNDLE_FAMILY_3', TRUE, 10),
    ('FAMILY', 'U3', 'UBOT_BUNDLE_FAMILY_3', FALSE, 10),
    ('FAMILY', 'U4', 'UBOT_BUNDLE_FAMILY_3', FALSE, 10),
    ('YOUTH', 'U5', 'UBOT_BUNDLE_YOUTH_2', TRUE, 8),
    ('YOUTH', 'U6', 'UBOT_BUNDLE_YOUTH_2', FALSE, 8),
    ('TABLET', 'U7', 'UBOT_BUNDLE_TABLET', TRUE, 9),
    ('TABLET', 'U7T', 'UBOT_BUNDLE_TABLET', FALSE, 9),
    ('WATCH', 'U8', 'UBOT_BUNDLE_WATCH', TRUE, 7),
    ('WATCH', 'U8W', 'UBOT_BUNDLE_WATCH', FALSE, 7),
    ('FRIEND', 'U9', 'UBOT_BUNDLE_FRIEND_2', TRUE, 5),
    ('FRIEND', 'U10', 'UBOT_BUNDLE_FRIEND_2', FALSE, 5);

INSERT INTO bundle_groups (representative_subscription_id, status, created_at, updated_at)
SELECT li.subscription_id, 'ACTIVE',
       date_trunc('day', now()) - make_interval(months => sb.months_ago),
       date_trunc('day', now()) - make_interval(months => sb.months_ago)
FROM seed_bundle sb
JOIN seed_line_id li ON li.line_key = sb.line_key
WHERE sb.representative;

INSERT INTO subscription_bundles (subscription_id, bundle_product_id, bundle_group_id, joined_at, status,
    product_name_snapshot, applied_discount_amount)
SELECT li.subscription_id, b.bundle_product_id, g.bundle_group_id,
       date_trunc('day', now()) - make_interval(months => sb.months_ago), 'ACTIVE', b.name,
       CASE WHEN sb.representative THEN b.discount_amount ELSE 0 END
FROM seed_bundle sb
JOIN seed_line_id li ON li.line_key = sb.line_key
JOIN bundle_products b ON b.code = sb.bundle_code
JOIN seed_bundle rep ON rep.group_key = sb.group_key AND rep.representative
JOIN seed_line_id rep_line ON rep_line.line_key = rep.line_key
JOIN bundle_groups g ON g.representative_subscription_id = rep_line.subscription_id AND g.status = 'ACTIVE';

-- 로밍: 예약, 이용 중, 지난 이용을 하나씩 둔다.
CREATE TEMP TABLE seed_roaming (line_key TEXT, roaming_code TEXT, start_offset INT, end_offset INT,
    status TEXT) ON COMMIT DROP;
INSERT INTO seed_roaming VALUES
    ('U1', 'UBOT_ROAM_JP_2G', 14, 18, 'SCHEDULED'),
    ('U6', 'UBOT_ROAM_US_1G', -2, 3, 'ACTIVE'),
    ('U9', 'UBOT_ROAM_VN_5G', -40, -35, 'EXPIRED');

INSERT INTO subscription_roamings (subscription_id, roaming_product_id, start_date, end_date, status,
    product_name_snapshot, daily_fee_snapshot, data_amount_mb_snapshot)
SELECT li.subscription_id, r.roaming_product_id, CURRENT_DATE + sr.start_offset, CURRENT_DATE + sr.end_offset,
       sr.status, r.name, r.daily_fee, r.data_amount_mb
FROM seed_roaming sr
JOIN seed_line_id li ON li.line_key = sr.line_key
JOIN roaming_products r ON r.code = sr.roaming_code;

-- 약정: 선택약정은 요금제 월정액의 25% 할인, 공시지원금 약정은 단말 할부를 함께 둔다.
CREATE TEMP TABLE seed_contract (line_key TEXT, contract_type TEXT, months_ago INT, term_months INT,
    status TEXT, device_name TEXT, device_total_amount INT) ON COMMIT DROP;
INSERT INTO seed_contract VALUES
    ('U1', 'DEVICE_SUBSIDY', 10, 24, 'ACTIVE', 'Ubot Phone S26', 720000),
    ('U2', 'SELECTIVE_CONTRACT', 14, 24, 'ACTIVE', NULL, NULL),
    ('U5', 'SELECTIVE_CONTRACT', 5, 12, 'ACTIVE', NULL, NULL),
    ('U8', 'DEVICE_SUBSIDY', 16, 24, 'ACTIVE', 'Ubot Phone S26 Ultra', 1200000),
    ('U9', 'SELECTIVE_CONTRACT', 30, 24, 'EXPIRED', NULL, NULL);

INSERT INTO subscription_contracts (subscription_id, contract_type, started_at, expires_at, discount_amount, status,
    device_name, device_total_amount, installment_months, paid_installment_months, monthly_installment_amount,
    remaining_installment_amount, installment_started_at, installment_expires_at, installment_status)
SELECT li.subscription_id, c.contract_type, d.started_at, d.expires_at,
       CASE WHEN c.contract_type = 'SELECTIVE_CONTRACT' THEN p.monthly_fee / 4 ELSE 0 END,
       c.status, c.device_name, c.device_total_amount,
       CASE WHEN c.device_name IS NULL THEN NULL ELSE c.term_months END,
       CASE WHEN c.device_name IS NULL THEN NULL ELSE LEAST(c.months_ago, c.term_months) END,
       CASE WHEN c.device_name IS NULL THEN NULL ELSE c.device_total_amount / c.term_months END,
       CASE WHEN c.device_name IS NULL THEN NULL
            ELSE c.device_total_amount - c.device_total_amount / c.term_months * LEAST(c.months_ago, c.term_months) END,
       CASE WHEN c.device_name IS NULL THEN NULL ELSE d.started_at END,
       CASE WHEN c.device_name IS NULL THEN NULL ELSE d.expires_at END,
       CASE WHEN c.device_name IS NULL THEN NULL ELSE 'ACTIVE' END
FROM seed_contract c
JOIN seed_line_id li ON li.line_key = c.line_key
JOIN subscriptions s ON s.subscription_id = li.subscription_id
JOIN plans p ON p.plan_id = s.plan_id
CROSS JOIN LATERAL (
    SELECT (date_trunc('month', CURRENT_DATE) - make_interval(months => c.months_ago))::DATE AS started_at,
           (date_trunc('month', CURRENT_DATE) - make_interval(months => c.months_ago)
               + make_interval(months => c.term_months) - INTERVAL '1 day')::DATE AS expires_at
) d;

-- 사용량: 이번 달(어제까지 집계, 미확정)과 지난 3개월(확정). 사용률은 회선·달마다 35~94%로 고정한다.
INSERT INTO usage_records (subscription_id, billing_month, data_used_mb, voice_used_minutes, sms_used_count,
    allowance_snapshot, aggregated_through_date, finalized_at, full_month_active, updated_at)
SELECT li.subscription_id, m.month_start,
       CASE WHEN p.data_unlimited THEN 15000 + u.pct * 900 ELSE p.data_amount_mb * u.pct / 100 END / m.divisor,
       CASE WHEN p.voice_unlimited THEN 80 + u.pct * 4 ELSE p.voice_minutes * u.pct / 100 END / m.divisor,
       CASE WHEN p.sms_unlimited THEN u.pct / 3 ELSE p.sms_count * u.pct / 100 END / m.divisor,
       jsonb_build_object(
           'dataAmountMb', CASE WHEN p.data_unlimited THEN NULL ELSE p.data_amount_mb END,
           'dataUnlimited', p.data_unlimited,
           'voiceMinutes', CASE WHEN p.voice_unlimited THEN NULL ELSE p.voice_minutes END,
           'voiceUnlimited', p.voice_unlimited,
           'smsCount', CASE WHEN p.sms_unlimited THEN NULL ELSE p.sms_count END,
           'smsUnlimited', p.sms_unlimited),
       CASE WHEN m.months_ago > 0 THEN (m.month_start + INTERVAL '1 month' - INTERVAL '1 day')::DATE
            WHEN EXTRACT(DAY FROM CURRENT_DATE) > 1 THEN CURRENT_DATE - 1 END,
       CASE WHEN m.months_ago > 0 THEN m.month_start + INTERVAL '1 month' END,
       CASE WHEN m.months_ago > 0 THEN TRUE END,
       CASE WHEN m.months_ago > 0 THEN m.month_start + INTERVAL '1 month' ELSE date_trunc('day', now()) END
FROM seed_line_id li
JOIN subscriptions s ON s.subscription_id = li.subscription_id
JOIN plans p ON p.plan_id = s.plan_id
CROSS JOIN LATERAL (
    SELECT k AS months_ago, (date_trunc('month', CURRENT_DATE) - make_interval(months => k))::DATE AS month_start,
           CASE WHEN k = 0 THEN 2 ELSE 1 END AS divisor
    FROM generate_series(0, 3) AS k
) m
CROSS JOIN LATERAL (SELECT 35 + abs(hashtext(li.line_key || ':' || m.months_ago)) % 60 AS pct) u;

-- 청구: 지난 3개월 사용분. 청구서는 사용한 다음 달 1일에 나오고 25일이 납부 기한이다.
-- 총액 = 기본료 + 부가서비스 + 로밍 + 기타(단말 할부) - 할인(결합 대표 회선 + 선택약정).
-- user5의 이번 달 청구서는 미납(기한이 지나면 연체), user6의 지난달 청구서는 연체로 둔다.
CREATE TEMP TABLE seed_billing ON COMMIT DROP AS
SELECT li.line_key, li.subscription_id, m.months_ago, m.month_start,
       p.monthly_fee AS base_fee,
       COALESCE((SELECT sum(a.monthly_fee_snapshot) FROM subscription_addons a
                 WHERE a.subscription_id = li.subscription_id AND a.status = 'ACTIVE'
                   AND a.subscribed_at < m.month_start + INTERVAL '1 month'), 0)::INT AS addon_fee,
       COALESCE((SELECT sum((r.end_date - r.start_date + 1) * r.daily_fee_snapshot) FROM subscription_roamings r
                 WHERE r.subscription_id = li.subscription_id AND r.status IN ('ACTIVE', 'EXPIRED')
                   AND r.start_date >= m.month_start AND r.start_date < m.month_start + INTERVAL '1 month'), 0)::INT AS roaming_fee,
       COALESCE((SELECT sum(c.monthly_installment_amount) FROM subscription_contracts c
                 WHERE c.subscription_id = li.subscription_id AND c.installment_status = 'ACTIVE'
                   AND c.installment_started_at <= m.month_start AND c.installment_expires_at >= m.month_start), 0)::INT AS extra_fee,
       (COALESCE((SELECT sum(b.applied_discount_amount) FROM subscription_bundles b
                  WHERE b.subscription_id = li.subscription_id AND b.status = 'ACTIVE'
                    AND b.joined_at < m.month_start), 0)
        + COALESCE((SELECT sum(c.discount_amount) FROM subscription_contracts c
                    WHERE c.subscription_id = li.subscription_id AND c.contract_type = 'SELECTIVE_CONTRACT'
                      AND c.started_at <= m.month_start AND c.expires_at >= m.month_start), 0))::INT AS discount_amount,
       (m.month_start + INTERVAL '1 month')::DATE AS issued_month,
       (m.month_start + INTERVAL '1 month' + INTERVAL '24 days')::DATE AS due_date,
       NOT ((li.line_key = 'U5' AND m.months_ago = 1) OR (li.line_key = 'U6' AND m.months_ago = 2)) AS paid
FROM seed_line_id li
JOIN subscriptions s ON s.subscription_id = li.subscription_id
JOIN plans p ON p.plan_id = s.plan_id
CROSS JOIN LATERAL (
    SELECT k AS months_ago, (date_trunc('month', CURRENT_DATE) - make_interval(months => k))::DATE AS month_start
    FROM generate_series(1, 3) AS k
) m;

INSERT INTO billing_records (subscription_id, billing_month, base_fee, addon_fee, roaming_fee, extra_fee,
    discount_amount, total_amount, due_date, payment_status, paid_amount, payment_method, paid_at, created_at, updated_at)
SELECT b.subscription_id, b.issued_month, b.base_fee, b.addon_fee, b.roaming_fee, b.extra_fee, b.discount_amount,
       t.total, b.due_date,
       CASE WHEN b.paid THEN 'PAID' WHEN b.due_date >= CURRENT_DATE THEN 'UNPAID' ELSE 'OVERDUE' END,
       CASE WHEN b.paid THEN t.total ELSE 0 END,
       CASE WHEN b.paid AND t.total > 0 THEN (ARRAY['AUTO_TRANSFER', 'CARD', 'ACCOUNT'])[1 + abs(hashtext(b.line_key)) % 3] END,
       CASE WHEN b.paid AND t.total > 0
            THEN LEAST(b.issued_month + INTERVAL '4 days', date_trunc('day', now())) END,
       b.issued_month,
       b.issued_month
FROM seed_billing b
CROSS JOIN LATERAL (SELECT GREATEST(b.base_fee + b.addon_fee + b.roaming_fee + b.extra_fee - b.discount_amount, 0) AS total) t;

-- 가상 사용자 300명의 청구 월 옮기기: 원본은 사용한 달로 적었으므로 한 달 뒤(청구서가 나온 달)로 옮긴다.
-- 원본의 생성일·납부 기한은 이미 청구서가 나온 달 기준이다. 회선·월 유일 조건에 걸리지 않도록 두 번에 나눠 옮긴다.
UPDATE billing_records r SET billing_month = (r.billing_month + INTERVAL '1200 months')::DATE
FROM subscriptions s JOIN users u ON u.user_id = s.user_id
WHERE s.subscription_id = r.subscription_id AND u.email LIKE 'ubot.demo.%@example.test';
UPDATE billing_records r SET billing_month = (r.billing_month - INTERVAL '1199 months')::DATE
FROM subscriptions s JOIN users u ON u.user_id = s.user_id
WHERE s.subscription_id = r.subscription_id AND u.email LIKE 'ubot.demo.%@example.test';

-- 결합 그룹 복원: 그룹이 없는 이용 중 결합 기록은 대표 회선에만 할인액이 있고 구성 회선은 0원이다.
-- 같은 결합 상품에서 사용자·할인액 순으로 정렬하면 대표 회선 뒤에 구성 회선이 온다.
CREATE TEMP TABLE legacy_bundle ON COMMIT DROP AS
SELECT sb.subscription_bundle_id, sb.subscription_id, sb.bundle_product_id, sb.applied_discount_amount, sb.joined_at,
       sum(CASE WHEN sb.applied_discount_amount > 0 THEN 1 ELSE 0 END) OVER (
           PARTITION BY sb.bundle_product_id
           ORDER BY s.user_id, sb.applied_discount_amount DESC, sb.subscription_bundle_id) AS group_no
FROM subscription_bundles sb
JOIN subscriptions s ON s.subscription_id = sb.subscription_id
WHERE sb.bundle_group_id IS NULL AND sb.status = 'ACTIVE';

INSERT INTO bundle_groups (representative_subscription_id, status, created_at, updated_at)
SELECT subscription_id, 'ACTIVE', joined_at, joined_at
FROM legacy_bundle
WHERE applied_discount_amount > 0;

UPDATE subscription_bundles sb SET bundle_group_id = g.bundle_group_id
FROM legacy_bundle lb
JOIN legacy_bundle rep ON rep.bundle_product_id = lb.bundle_product_id AND rep.group_no = lb.group_no
    AND rep.applied_discount_amount > 0
JOIN bundle_groups g ON g.representative_subscription_id = rep.subscription_id AND g.status = 'ACTIVE'
WHERE sb.subscription_bundle_id = lb.subscription_bundle_id;

-- 결합은 본인 명의 회선만 묶는다. 다른 사람의 회선이 섞인 이용 중 결합(원본 데이터의 친구·가족 결합과
-- 위 user 계정의 가족·청년·친구 결합)은 초기화 시점에 종료한다. 기록은 ENDED로 남긴다.
CREATE TEMP TABLE cross_user_group ON COMMIT DROP AS
SELECT sb.bundle_group_id
FROM subscription_bundles sb
JOIN subscriptions s ON s.subscription_id = sb.subscription_id
JOIN bundle_groups g ON g.bundle_group_id = sb.bundle_group_id AND g.status = 'ACTIVE'
WHERE sb.status = 'ACTIVE'
GROUP BY sb.bundle_group_id
HAVING count(DISTINCT s.user_id) > 1;

UPDATE subscription_bundles sb
SET status = 'ENDED', ended_at = GREATEST(sb.joined_at, date_trunc('day', now())), updated_at = now()
FROM cross_user_group c
WHERE sb.bundle_group_id = c.bundle_group_id AND sb.status = 'ACTIVE';

UPDATE bundle_groups g
SET status = 'ENDED', ended_at = date_trunc('day', now()), updated_at = now()
FROM cross_user_group c
WHERE g.bundle_group_id = c.bundle_group_id;

-- 요금제 포함 부가서비스: 두 시드 파일의 해지되지 않은 회선 모두. 앱의 요금제 변경과 같은 규칙을 따른다.
-- 포함 부가서비스를 따로 가입해 두었으면 해지하고, 요금제 이용 시작 시점에 0원(PLAN_INCLUDED)으로 넣는다.
CREATE TEMP TABLE seed_included ON COMMIT DROP AS
SELECT s.subscription_id, pia.addon_service_id, a.name,
       GREATEST(COALESCE(s.plan_changed_at, s.activated_at), s.activated_at) AS included_at
FROM subscriptions s
JOIN plan_included_addons pia ON pia.plan_id = s.plan_id
JOIN addon_services a ON a.addon_service_id = pia.addon_service_id AND a.deleted_at IS NULL
WHERE s.status <> 'TERMINATED';

UPDATE subscription_addons sa SET status = 'CANCELED', canceled_at = GREATEST(sa.subscribed_at, i.included_at)
FROM seed_included i
WHERE sa.subscription_id = i.subscription_id AND sa.addon_service_id = i.addon_service_id
  AND sa.status = 'ACTIVE' AND sa.source = 'SUBSCRIBED';

INSERT INTO subscription_addons (subscription_id, addon_service_id, subscribed_at, status,
    service_name_snapshot, monthly_fee_snapshot, source)
SELECT i.subscription_id, i.addon_service_id,
       GREATEST(i.included_at, (SELECT max(sa.canceled_at) FROM subscription_addons sa
                                WHERE sa.subscription_id = i.subscription_id
                                  AND sa.addon_service_id = i.addon_service_id)),
       'ACTIVE', i.name, 0, 'PLAN_INCLUDED'
FROM seed_included i;

COMMIT;
