-- 로컬 초기화용 사용자 10명과 가입 상품. reset-local-db.ps1이 seed-baseline.sql 다음에 실행한다.
-- 상품은 V43 카탈로그를 코드로 찾는다. 비밀번호는 기본 계정과 같은 12345678이다.
-- user@user.com은 seed-baseline.sql이 만들고, user2@user.com ~ user10@user.com을 여기서 만든다.
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

-- 부가서비스 (라이프 뮤직·북패스 요금제의 포함 혜택은 plan_included_addons에 있어 따로 가입하지 않는다)
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

COMMIT;
