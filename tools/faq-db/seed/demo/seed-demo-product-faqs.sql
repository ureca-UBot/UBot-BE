-- 상품 의도(PRODUCT_DATA) FAQ 시연 데이터. #275에서 바뀐 FAQ를 기존 DB에 덧붙인다.
--   - 새 FAQ 1905~2168과 그 질문 표현은 기본 CSV(/tmp/baseline-faqs.csv, /tmp/baseline-faq-variants.csv)에서 읽는다.
--   - #275가 바꾼 기존 FAQ 76개(59개는 GENERAL → PRODUCT_DATA)와 질문 표현 16개는 CSV 값으로 바꾼다.
--     아래 변경 전 값은 #305에서 삭제된 seed/days/updated-faq-1022.json으로 만들었다. 손으로 고치지 않는다.
-- 인기 FAQ 시연(seed-demo-faqranking.sql)이 2084~2155번 FAQ를 쓰므로 그보다 먼저 적용한다.
-- 새 FAQ가 이미 있으면(기본 CSV로 초기화한 DB 등) 아무것도 바꾸지 않는다.
-- 그 밖에는 FAQ·질문 표현 ID가 비어 있고 바꿀 FAQ가 변경 전 값 그대로일 때만 적용하며, 아니면 전체를 실패시켜 관리자 수정을 덮어쓰지 않는다.
-- 바꾼 내용은 demo_product_faq_backup에 남겨 clear-demo-activity.ps1이 되돌린다. 벡터는 넣지 않으므로 적용 뒤 백필한다.
\if :{?managed_transaction}
\else
BEGIN;
\endif
SET LOCAL client_encoding = 'UTF8';

CREATE TEMP TABLE seed_product_faq (faq_id BIGINT, category TEXT, question TEXT, answer TEXT, intent TEXT) ON COMMIT DROP;
CREATE TEMP TABLE seed_product_variant (origin_faq_id BIGINT, variant_id BIGINT, question TEXT) ON COMMIT DROP;
\copy seed_product_faq (faq_id, category, question, answer, intent) FROM '/tmp/baseline-faqs.csv' WITH (FORMAT csv, HEADER true)
\copy seed_product_variant (origin_faq_id, variant_id, question) FROM '/tmp/baseline-faq-variants.csv' WITH (FORMAT csv, HEADER true)

CREATE TEMP TABLE product_before_faq (faq_id BIGINT PRIMARY KEY, category TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL, intent TEXT NOT NULL) ON COMMIT DROP;
INSERT INTO product_before_faq (faq_id, category, question, answer, intent) VALUES
  (103, '요금제', '태블릿에서 쓸 수 있는 요금제가 있나요?', '태블릿은 데이터 전용 Ubot 태블릿 요금제를 이용할 수 있습니다. Ubot 태블릿 요금제는 5GB 월 9,900원부터 50GB 월 29,900원까지 있으며 음성·문자는 제공하지 않습니다. 스마트워치는 별도의 Ubot 워치 요금제를 이용할 수 있습니다.', 'GENERAL'),
  (113, '요금제', '5G 스탠다드에 약정 할인을 받으면 월 요금이 얼마인가요?', '5G 스탠다드 월 75,000원에 25% 할인이 적용되어 월 56,250원입니다.', 'GENERAL'),
  (170, '데이터/속도', '테더링으로 쓴 데이터는 어디에서 확인하나요?', '테더링으로 사용한 데이터는 앱의 사용량 화면에서 쉐어링 데이터와 합산된 사용량으로 볼 수 있습니다. 테더링과 쉐어링에 제공되는 데이터는 5G 프리미엄 요금제에서 월 50GB, 5G 스탠다드 요금제에서 월 30GB입니다.', 'GENERAL'),
  (181, '데이터/속도', '태블릿용 데이터 회선을 새로 만들려면 어떻게 하나요?', '태블릿용 데이터 회선은 앱이나 매장에서 Ubot 태블릿 요금제로 개통할 수 있습니다. Ubot 태블릿 요금제는 5GB 월 9,900원부터 50GB 월 29,900원까지 있으며 음성·문자는 제공하지 않습니다.', 'GENERAL'),
  (188, '통화/문자', '문자는 요금제마다 몇 건까지 무료인가요?', 'LTE 세이브 요금제는 문자 100건을 제공하며 초과분은 건당 22원입니다. LTE 세이브를 제외한 나머지 요금제는 문자를 기본 제공합니다.', 'GENERAL'),
  (218, '약정/해지', '가족 중 한 명이 해지하면 결합 할인은 어떻게 되나요?', '가족 결합 회선 중 하나를 해지하면 남은 회선 수에 맞는 할인 금액으로 다음 달부터 바뀝니다. 예를 들어 가족 결합이 4회선에서 3회선이 되면 대표 회선의 월 할인 금액이 20,000원에서 15,000원으로 바뀝니다. 가족 결합 회선이 2개 미만으로 줄면 결합이 종료됩니다.', 'GENERAL'),
  (273, '부가서비스', '가족 위치 알림을 그만 쓰고 싶어요.', '가족 위치 알림 서비스는 앱이나 고객센터에서 즉시 해지할 수 있습니다. 가족 위치 알림 서비스의 월 요금 1,650원은 사용한 일수만큼 나누어 청구됩니다.', 'GENERAL'),
  (275, '부가서비스', '넘버플러스를 쓰면 요금제를 하나 더 가입해야 하나요?', '넘버플러스는 기존 회선에 추가하는 부가서비스라 별도 요금제에 가입하지 않아도 됩니다. 넘버플러스를 이용하면 월 3,300원이 청구됩니다.', 'GENERAL'),
  (298, '결합상품', '가족 결합을 하면 얼마나 할인되나요?', 'Ubot 가족 결합은 휴대폰 2회선이면 대표 회선에서 월 6,000원, 3회선이면 월 15,000원, 4회선이면 월 20,000원, 5회선이면 월 24,000원을 할인합니다. 가족 결합 할인은 회선마다 따로 적용되지 않고 대표 회선에서 한 번에 차감됩니다.', 'GENERAL'),
  (299, '결합상품', '4회선을 묶으면 전체로 얼마나 아끼나요?', 'Ubot 가족 결합으로 휴대폰 4회선을 묶으면 대표 회선에서 월 20,000원이 할인됩니다. 할인은 네 회선에 나눠 적용되지 않고 가족 전체 요금에서 월 20,000원이 줄어드는 효과가 있습니다.', 'GENERAL'),
  (300, '결합상품', '인터넷과 IPTV까지 묶으면 얼마나 할인되나요?', '휴대폰과 인터넷을 홈 결합하면 인터넷 속도에 따라 휴대폰 요금에서 월 3,300원부터 7,700원까지 할인됩니다. 인터넷과 IPTV를 함께 결합할 때의 IPTV 할인은 가입 상품의 조건을 확인해 주세요.', 'GENERAL'),
  (305, '결합상품', '가족 결합과 인터넷 결합을 함께 받을 수 있나요?', '가족 결합과 휴대폰·인터넷 홈 결합은 가입 조건을 충족하면 함께 신청할 수 있습니다. 가족 결합 할인은 대표 휴대폰 회선에서 차감됩니다. 홈 결합 할인은 인터넷 속도에 따라 휴대폰 요금에서 월 3,300원부터 7,700원까지 적용됩니다.', 'GENERAL'),
  (313, '결합상품', '결합으로 받을 수 있는 할인을 정리해 주세요.', 'Ubot 가족 결합은 회선 수에 따라 대표 회선에서 월 6,000원부터 24,000원까지 할인됩니다. 휴대폰과 인터넷의 홈 결합 할인은 인터넷 속도에 따라 휴대폰 요금에서 월 3,300원부터 7,700원까지 적용됩니다. 인터넷과 IPTV를 결합하면 상품 조건에 따른 할인 금액은 가입 내역에서 확인할 수 있습니다.', 'GENERAL'),
  (1030, '부가서비스', '휴대폰이 꺼져 있을 때 온 전화를 알려 주는 서비스가 있나요?', '부재중 알림 서비스를 신청하면 휴대폰 전원이 꺼져 있거나 통화가 불가능한 동안 걸려온 전화의 수신 기록을 알려 줍니다. 부재중 알림 서비스의 월 요금은 부가세를 포함해 550원입니다.', 'GENERAL'),
  (1031, '부가서비스', '착신전환은 어떻게 신청하고 요금은 얼마인가요?', '착신전환 부가서비스를 신청하면 음성 전화를 지정한 국내 번호로 돌릴 수 있으며 월 요금은 부가세를 포함해 1,100원입니다. 착신전환은 국제전화나 유료 특수번호로 설정할 수 없습니다.', 'GENERAL'),
  (1032, '부가서비스', '피싱이 의심되는 링크나 전화를 알려 주는 서비스가 있나요?', '피싱 차단 부가서비스를 신청하면 위험 링크와 의심 전화를 알림으로 받을 수 있으며 월 요금은 부가세를 포함해 2,200원입니다. 피싱 차단 부가서비스는 금융 피해에 대한 보험이나 배상을 제공하지 않습니다.', 'GENERAL'),
  (1033, '부가서비스', '디바이스 케어는 어떤 서비스인가요?', '디바이스 케어는 단말기 상태를 점검하고 원격으로 이용 상담을 받는 서비스로 월 요금은 부가세를 포함해 4,400원입니다. 디바이스 케어에는 수리비 보상, 분실 보험, 기기 교환이 포함되지 않습니다.', 'GENERAL'),
  (1104, '요금제', 'Ubot 프리미엄 요금제는 어떤 요금제인가요?', 'Ubot 프리미엄은 고속 데이터를 무제한으로 쓰는 요금제로 89(월 89,000원), 99(99,000원), 109(109,000원), Pro(119,000원), Max(129,000원)가 있습니다. Ubot 프리미엄 요금제는 음성·문자를 기본 제공하며 테더링은 89·99·109·Pro·Max 순서로 각각 월 60GB, 80GB, 100GB, 120GB, 140GB까지 이용할 수 있습니다.', 'GENERAL'),
  (1105, '요금제', 'Ubot 밸런스 요금제는 어떤 요금제인가요?', 'Ubot 밸런스는 기본 데이터를 다 쓰면 속도 제한으로 계속 사용하는 요금제입니다. Ubot 밸런스는 79(월 79,000원, 250GB 후 5Mbps), 69(69,000원, 110GB 후 5Mbps), 59(59,000원, 24GB 후 1Mbps), 55(55,000원, 15GB), 49(49,000원, 11GB), 45(45,000원, 8GB), 39(39,000원, 6GB)가 있으며 음성·문자는 기본 제공됩니다.', 'GENERAL'),
  (1106, '요금제', '밸런스 59 데이터업 요금제는 무엇이 다른가요?', '밸런스 59 데이터업은 밸런스 59에 데이터를 더한 요금제로 13GB업(월 62,000원, 37GB), 30GB업(64,000원, 54GB), 50GB업(66,000원, 74GB), 75GB업(68,000원, 99GB)이 있습니다. 밸런스 59 데이터업 요금제는 모두 데이터를 다 쓰면 1Mbps로 계속 이용할 수 있고 테더링은 월 24GB입니다.', 'GENERAL'),
  (1107, '요금제', 'Ubot 베이직 요금제는 어떤 요금제인가요?', 'Ubot 베이직은 월 28,900원부터 100,000원까지 14종이며 베이직 80·100은 데이터 무제한이고 나머지는 600MB~110GB를 쓴 뒤 속도 제한으로 계속 이용합니다. Ubot 베이직 28.9만 음성 180분·문자 180건을 제공하며 나머지 베이직 요금제는 음성·문자를 기본 제공합니다.', 'GENERAL'),
  (1108, '요금제', 'Ubot 다이렉트 요금제 종류가 궁금해요.', 'Ubot 다이렉트는 온라인 셀프개통 전용 무약정 요금제로 월 30,000원(8GB)부터 69,000원(무제한)까지 13종입니다. 다이렉트 61·69는 데이터를 무제한으로 이용할 수 있고 다이렉트 49·55는 각각 120GB·200GB를 쓴 뒤 5Mbps로 계속 이용할 수 있습니다.', 'GENERAL'),
  (1109, '요금제', 'Ubot 플러스 요금제는 어떤 요금제인가요?', 'Ubot 플러스는 데이터 무제한 요금제로 95(월 95,000원), 105(105,000원), 115(115,000원), 130(130,000원)이 있습니다. 플러스 요금제의 테더링 한도는 95가 월 80GB, 105·115가 월 100GB, 130이 월 120GB입니다.', 'GENERAL'),
  (1110, '요금제', 'Ubot 데이터 요금제 시리즈는 어떤 요금제인가요?', 'Ubot 데이터 시리즈는 300MB(월 28,000원)부터 150GB(월 75,000원)까지 데이터 제공량에 따라 고르는 요금제이며 데이터 MAX(월 85,000원)는 무제한입니다. 데이터 시리즈 중 300MB·750MB는 음성 125분·문자 150건을 제공하고 나머지는 음성·문자를 기본 제공합니다.', 'GENERAL'),
  (1111, '요금제', 'Ubot 실속 요금제는 어떤 요금제인가요?', 'Ubot 실속은 약정 없는 저가 요금제로 LTE 10종(월 3,300원~9,900원)과 5G 3종(2GB 6,000원, 3GB 6,900원, 4GB 7,800원)이 있습니다. Ubot 실속에서 이름에 +가 붙은 요금제는 데이터를 다 쓴 뒤 1Mbps로 계속 이용할 수 있고 나머지 요금제는 데이터가 차단됩니다.', 'GENERAL'),
  (1112, '요금제', '통화만 하는 요금제가 있나요?', 'Ubot 통화 전용은 월 4,900원에 데이터 없이 국내 음성과 문자를 기본 제공하는 LTE 요금제입니다. 통화 중심이지만 데이터를 조금이라도 써야 한다면 실속 LTE 1.5GB/통화기본(월 5,900원)을 이용할 수 있습니다.', 'GENERAL'),
  (1113, '요금제', '북패스 요금제는 어떤 요금제인가요?', '북패스 요금제에는 Ubot 북패스 전자책 이용권 1개가 포함되며 7GB+(월 16,300원), 10GB+(19,000원), 15GB+(21,500원), 100GB+(38,300원)가 있습니다. 북패스의 기본 데이터를 다 쓰면 7GB+~15GB+는 1Mbps, 100GB+는 5Mbps로 계속 이용할 수 있습니다.', 'GENERAL'),
  (1114, '요금제', '청년 요금제 종류와 가격이 궁금해요.', '청년 요금제는 만 19~34세 전용으로 5GB(월 19,000원), 10GB(25,000원), 30GB(33,000원), 100GB(45,000원), 200GB(55,000원), 무제한(65,000원)이 있습니다. 청년 요금제는 모두 음성·문자를 기본 제공하며 100GB·200GB는 데이터를 다 쓴 뒤 5Mbps로 계속 이용할 수 있습니다.', 'GENERAL'),
  (1115, '요금제', '시니어 안심 요금제 종류와 가격이 궁금해요.', '시니어 안심은 만 65세 이상 전용 LTE 요금제로 1GB(월 3,900원), 2GB(6,900원), 4GB(8,900원), 8GB(13,900원), 15GB(19,900원)가 있습니다. 시니어 안심 1GB는 음성 100분·문자 100건을 제공하며 나머지 시니어 안심 요금제는 음성·문자를 기본 제공합니다.', 'GENERAL'),
  (1116, '요금제', '키즈 안심 요금제 종류와 가격이 궁금해요.', '키즈 안심은 만 12세 이하 전용 요금제로 1GB(월 6,900원), 2GB(9,900원), 4GB(12,900원), 8GB(19,000원)가 있으며 Ubot 키즈가드가 포함됩니다. 키즈 안심 가입에는 법정대리인 동의가 필요하고 테더링은 제공하지 않습니다.', 'GENERAL'),
  (1117, '요금제', '워치 요금제 종류와 가격이 궁금해요.', 'Ubot 워치 요금제는 0.5GB(월 5,500원, 음성 50분·문자 100건), 1GB(8,800원, 음성 100분·문자 250건), 2GB(11,000원, 음성 200분·문자 500건)가 있습니다. Ubot 워치 요금제는 데이터를 다 쓰면 차단되며 같은 명의의 Ubot 휴대폰과 연결해야 이용할 수 있습니다.', 'GENERAL'),
  (1118, '요금제', '태블릿 요금제 종류와 가격이 궁금해요.', 'Ubot 태블릿 요금제는 데이터 전용 5G 상품으로 5GB(월 9,900원), 10GB(14,900원), 20GB(19,900원), 50GB(29,900원)가 있습니다. Ubot 태블릿 요금제는 음성·문자를 제공하지 않으며 데이터를 다 쓰면 차단됩니다.', 'GENERAL'),
  (1119, '요금제', 'Ubot 라이프 요금제는 어떤 혜택이 있나요?', 'Ubot 라이프는 뮤직·비디오·커피·교통·쇼핑·보안 중 혜택 하나가 포함된 LTE 요금제입니다. 라이프 뮤직 15GB는 월 22,900원에 뮤직패스, 비디오 100GB는 월 42,900원에 비디오패스, 커피 10GB는 월 21,900원에 카페 5,000포인트, 교통 20GB는 월 25,900원에 교통지갑 5,000포인트, 쇼핑 30GB는 월 29,900원에 스토어 5,000포인트, 보안 10GB는 월 20,900원에 피싱가드를 제공합니다.', 'GENERAL'),
  (1121, '요금제', '월 5만 원 정도 요금제 중에 데이터를 가장 많이 주는 것은 무엇인가요?', '월 5만 원 안팎 요금제 중 다이렉트 49는 월 49,000원에 데이터 120GB를 제공하고 소진 후 5Mbps로 이용하는 온라인 전용 상품입니다. 월 5만 원 안팎의 매장 가입 요금제로는 밸런스 49(11GB)와 베이직 50(10GB)이 있습니다. 만 19~34세라면 월 45,000원에 데이터 100GB를 제공하는 청년 100GB 요금제도 이용할 수 있습니다.', 'GENERAL'),
  (1122, '요금제', '요금제마다 테더링은 얼마나 쓸 수 있나요?', '테더링 한도는 요금제마다 다르며 데이터 무제한 요금제는 월 40GB~140GB, 데이터 제공량이 정해진 요금제는 대부분 기본 제공량과 월 20GB 중 적은 쪽입니다. 사용 중인 요금제의 정확한 테더링 한도는 앱의 요금제 상세에서 확인할 수 있습니다.', 'GENERAL'),
  (1128, '요금제', '데이터를 많이 쓰는데 무제한 요금제가 있나요?', '속도 제한 없이 고속 데이터를 무제한으로 쓰는 요금제는 다이렉트 61(월 61,000원, 온라인 전용)·69, 베이직 80·100, 데이터 MAX, 프리미엄 89~Max, 플러스 95~130 등이 있습니다. 만 19~34세라면 청년 무제한(월 65,000원)도 이용할 수 있으며 무제한 요금제의 테더링 한도는 상품별로 월 40GB~140GB입니다.', 'GENERAL'),
  (1129, '요금제', '데이터를 다 쓰면 속도가 얼마나 느려지나요?', '데이터를 다 쓴 뒤의 속도는 요금제마다 다릅니다. 밸런스 79·69는 데이터를 소진한 뒤 최대 5Mbps, 밸런스 39나 일부 베이직 요금제는 최대 0.4Mbps로 계속 이용할 수 있습니다. 실속 LTE·태블릿·워치 요금제는 데이터를 모두 쓰면 차단됩니다.', 'GENERAL'),
  (1130, '요금제', '데이터를 적게 쓰는데 저렴한 요금제가 있나요?', '데이터를 거의 쓰지 않는다면 실속 LTE 500MB/60분(월 3,300원)이나 통화와 문자만 쓰는 Ubot 통화 전용(월 4,900원)을 이용할 수 있습니다. 저렴한 5G 요금제로는 실속 5G 2GB/통화기본(월 6,000원)이 있으며 실속 요금제는 데이터를 다 쓰면 차단되고 약정이 없습니다.', 'GENERAL'),
  (1131, '요금제', '온라인 전용 요금제가 있나요?', '온라인 셀프개통으로만 가입할 수 있는 Ubot 다이렉트는 월 30,000원~69,000원의 무약정 요금제입니다. Ubot 다이렉트는 같은 데이터 제공량의 매장 가입 요금제보다 월정액이 낮습니다.', 'GENERAL'),
  (1133, '요금제', '청년 요금제는 누가 가입할 수 있나요?', 'Ubot 청년 요금제는 만 19세부터 34세까지 가입할 수 있으며 청년 5GB(월 19,000원)부터 청년 무제한(월 65,000원)까지 있습니다. 청년 요금제는 만 35세가 되는 달 말일까지 이용할 수 있고 이후에는 일반 요금제로 변경해야 합니다.', 'GENERAL'),
  (1134, '요금제', '청소년이나 어린이용 요금제가 있나요?', '만 12세 이하 어린이는 Ubot 키즈 안심 요금제(월 6,900원~19,000원)에 가입할 수 있으며 자녀 위치 공유와 유해 사이트 차단을 제공하는 Ubot 키즈가드가 포함됩니다. 만 13세부터 18세까지의 청소년은 법정대리인 동의를 받아 일반 요금제에 가입할 수 있습니다.', 'GENERAL'),
  (1137, '요금제', '시니어 요금제는 몇 살부터 가입할 수 있나요?', '만 65세 이상이면 Ubot 시니어 안심 요금제(월 3,900원~19,900원)에 가입할 수 있습니다. 만 65세가 되어도 시니어 요금제로 자동 변경되지 않으므로 앱이나 고객센터에서 직접 신청해 주세요.', 'GENERAL'),
  (1138, '요금제', '태블릿이나 워치에서 쓸 수 있는 요금제가 있나요?', '태블릿은 데이터 전용 Ubot 태블릿 요금제(5GB 월 9,900원~50GB 월 29,900원)를 이용할 수 있습니다. 스마트워치는 Ubot 워치 요금제(0.5GB 월 5,500원~2GB 월 11,000원)를 이용할 수 있습니다. Ubot 태블릿 요금제와 워치 요금제는 모두 데이터를 다 쓰면 차단됩니다.', 'GENERAL'),
  (1151, '요금제', '데이터가 모자라면 추가로 살 수 있나요?', '데이터가 모자라면 Ubot 앱에서 데이터 플러스 1GB(월 3,300원), 5GB(월 9,900원), 10GB(월 14,900원) 중 하나에 가입할 수 있습니다. 데이터 플러스는 매월 자동 갱신되므로 더 필요하지 않을 때는 해지해 주세요.', 'GENERAL'),
  (1152, '요금제', '혜택이 포함된 요금제가 있나요?', 'Ubot 라이프 요금제에는 음악, 영상, 커피, 교통, 쇼핑, 보안 중 한 가지 혜택이 포함됩니다. 라이프 커피 10GB(월 21,900원)는 매월 Ubot 카페 5,000포인트를 제공하며 이 포인트는 발급한 달 말일에 만료됩니다.', 'GENERAL'),
  (1201, '데이터/속도', '데이터를 다 쓰면 어떻게 되나요?', '데이터를 모두 사용한 뒤의 처리 방식은 요금제마다 다릅니다. 대부분의 5G 요금제는 데이터를 다 쓰면 0.4~5Mbps의 정해진 속도로 추가 요금 없이 계속 이용할 수 있습니다. 실속 LTE·태블릿·워치 요금제는 데이터를 모두 쓰면 차단됩니다. 데이터 소진 뒤 더 빠른 속도로 쓰고 싶다면 데이터 플러스 상품을 추가해 주세요.', 'GENERAL'),
  (1210, '데이터/속도', '데이터를 가족이나 친구에게 선물할 수 있나요?', 'Ubot은 회선 간 데이터 선물 기능을 제공하지 않아 가족이나 친구에게 데이터를 보낼 수 없습니다. 데이터가 부족한 회선에는 데이터 플러스 1GB(월 3,300원)부터 추가할 수 있습니다.', 'GENERAL'),
  (1237, '통화/문자', '걸려 온 전화를 다른 번호로 돌릴 수 있나요?', 'Ubot 착신전환(월 1,100원)에 가입하면 걸려 온 음성 전화를 원하는 국내 번호로 돌릴 수 있습니다. 착신전환은 국제번호나 유료 특수번호로 설정할 수 없습니다. 착신전환으로 연결된 통화에는 신청한 회선 기준의 통화료가 부과됩니다.', 'GENERAL'),
  (1240, '통화/문자', '휴대폰이 꺼져 있을 때 온 전화를 알 수 있나요?', 'Ubot 부재중 알림(월 550원)에 가입하면 휴대폰이 꺼져 있거나 통화할 수 없을 때 걸려 온 전화 번호와 시간을 문자로 알려 드립니다.', 'GENERAL'),
  (1293, '해외 로밍', '일본, 중국, 동남아 같은 아시아 나라의 로밍 상품은 무엇이 있나요?', '일본, 중국, 대만, 홍콩, 베트남, 태국, 싱가포르, 필리핀에 아시아 국가별 전용 Ubot 로밍 상품이 있습니다. 아시아 국가별 로밍 상품은 데이터를 사용하는 24시간 구간마다 베이직 1GB 11,000원, 플러스 2GB 13,000원, 맥스 5GB 15,000원이 부과됩니다. 아시아 국가 로밍 상품은 방문하는 나라별로 따로 가입해야 합니다.', 'GENERAL'),
  (1294, '해외 로밍', '미국, 캐나다, 호주 로밍 상품은 무엇이 있나요?', '미국, 캐나다, 호주에 국가별 전용 Ubot 로밍 상품이 있습니다. 미주·오세아니아 국가별 로밍 상품은 데이터를 사용하는 24시간 구간마다 베이직 1GB 11,000원, 플러스 2GB 13,000원, 맥스 5GB 15,000원이 부과됩니다. 미국·캐나다·호주 로밍 상품은 방문하는 나라별로 따로 가입해야 합니다.', 'GENERAL'),
  (1295, '해외 로밍', '영국, 프랑스 같은 유럽 나라의 로밍 상품은 무엇이 있나요?', '영국, 프랑스, 독일, 스페인, 이탈리아에 유럽 국가별 전용 Ubot 로밍 상품이 있습니다. 유럽 국가별 로밍 상품은 데이터를 사용하는 24시간 구간마다 베이직 1GB 11,000원, 플러스 2GB 13,000원, 맥스 5GB 15,000원이 부과됩니다. 유럽 국가 로밍 상품은 방문하는 나라별로 따로 가입해야 합니다. 로밍 상품이 없는 다른 유럽 국가에서는 데이터 로밍 차단을 권장합니다.', 'GENERAL'),
  (1297, '해외 로밍', '로밍 상품 요금은 얼마인가요?', '로밍 상품 요금은 국가와 관계없이 데이터 사용 24시간 구간마다 베이직 1GB 11,000원, 플러스 2GB 13,000원, 맥스 5GB 15,000원입니다. 로밍 상품은 데이터를 쓴 24시간 구간에만 요금이 부과되며 음성통화와 문자 요금은 포함되지 않습니다.', 'GENERAL'),
  (1328, '부가서비스', '무료로 쓸 수 있는 부가서비스는 무엇이 있나요?', '발신번호 표시, 스팸 차단, 휴대폰 결제 차단, 국제전화 발신 차단, 데이터 로밍 차단, 통화중 대기는 월 0원으로 무료입니다.', 'GENERAL'),
  (1329, '부가서비스', '유료 부가서비스에는 무엇이 있나요?', '부재중 알림(550원), 컬러링(990원), 착신전환(1,100원), 듀얼번호(3,300원), 키즈가드(2,200원), 피싱가드(2,200원), 패밀리 안심(3,300원), 디바이스 케어(4,400원)와 북패스·뮤직패스·비디오패스 같은 콘텐츠 상품이 있습니다.', 'GENERAL'),
  (1330, '부가서비스', '컬러링은 어떻게 쓰나요?', 'Ubot 컬러링(월 990원)에 가입하면 전화를 건 사람에게 들리는 연결음을 기본 제공 음원 중에서 고를 수 있습니다. Ubot 컬러링의 유료 음원은 따로 구매하지 않습니다.', 'GENERAL'),
  (1333, '부가서비스', '듀얼번호는 어떤 서비스인가요?', 'Ubot 듀얼번호(월 3,300원)는 eSIM을 지원하는 휴대폰에서 보조 번호 1개를 함께 쓰는 서비스입니다. 듀얼번호 보조 번호로 사용한 통화·문자·데이터는 주 회선의 제공량에서 차감됩니다.', 'GENERAL'),
  (1334, '부가서비스', '가족 안부를 확인할 수 있는 서비스가 있나요?', 'Ubot 패밀리 안심(월 3,300원)에 가입하면 동의한 가족의 안부 확인과 위치 공유를 할 수 있습니다. Ubot 패밀리 안심은 의료 진단이나 응급 구조 기능을 제공하지 않습니다.', 'GENERAL'),
  (1335, '부가서비스', '음악이나 영상 콘텐츠 부가서비스가 있나요?', '음악·영상 콘텐츠 부가서비스로 Ubot 뮤직패스(월 6,900원), 비디오패스(월 9,900원), 북패스(월 4,900원)가 있습니다. 뮤직패스·비디오패스·북패스의 콘텐츠를 이용할 때 쓰는 데이터는 가입한 요금제에서 차감됩니다.', 'GENERAL'),
  (1342, '부가서비스', '통화를 더 쓰고 싶은데 추가할 수 있는 상품이 있나요?', 'Ubot 음성 플러스 100(월 3,300원)에 가입하면 국내 일반 음성통화를 매월 100분 더 사용할 수 있습니다. 음성 플러스 100은 국제·영상통화와 유료 특수번호를 제공하지 않으며 남은 시간은 다음 달로 이월되지 않습니다.', 'GENERAL'),
  (1343, '부가서비스', '뮤직패스는 어떤 서비스인가요?', 'Ubot 뮤직패스(월 6,900원)는 Ubot 음악 스트리밍을 이용하는 부가서비스입니다. 뮤직패스로 음악을 들을 때 쓰는 데이터는 가입한 요금제에서 차감됩니다. 라이프 뮤직 요금제에는 뮤직패스가 이미 포함되어 있습니다.', 'GENERAL'),
  (1344, '부가서비스', '비디오패스는 어떤 서비스인가요?', 'Ubot 비디오패스(월 9,900원)는 Ubot 영상 라이브러리를 1인이 이용하는 부가서비스이며 실제 OTT 제휴 상품은 아닙니다. 비디오패스로 영상을 볼 때 쓰는 데이터는 요금제에서 차감됩니다. 라이프 비디오 요금제에는 비디오패스가 포함되어 있습니다.', 'GENERAL'),
  (1345, '부가서비스', '북패스는 어떤 서비스인가요?', 'Ubot 북패스(월 4,900원)는 Ubot 전자책 라이브러리를 이용하는 부가서비스입니다. 북패스 요금제에는 북패스 이용권이 포함되어 있어 따로 가입하지 않아도 됩니다.', 'GENERAL'),
  (1346, '부가서비스', '피싱가드는 어떤 서비스인가요?', 'Ubot 피싱가드(월 2,200원)는 위험한 링크가 담긴 문자와 의심 전화를 미리 알려 주는 서비스입니다. 피싱가드는 금융 피해에 대한 보험이나 배상을 제공하지 않습니다. 라이프 보안 요금제에는 피싱가드가 포함되어 있습니다.', 'GENERAL'),
  (1347, '부가서비스', '키즈가드는 어떤 서비스인가요?', 'Ubot 키즈가드(월 2,200원)는 보호자가 연결·동의한 뒤 자녀 위치를 공유받고 유해 사이트를 차단하는 서비스입니다. 키즈가드는 긴급 출동 서비스를 제공하지 않습니다. 키즈 안심 요금제에는 키즈가드가 포함되어 있습니다.', 'GENERAL'),
  (1369, '결합상품', '가족 결합은 얼마나 할인되나요?', 'Ubot 가족 결합은 휴대폰 2회선이면 월 6,000원, 3회선이면 15,000원, 4회선이면 20,000원, 5회선이면 24,000원을 대표 회선에서 할인합니다. 가족 결합 할인은 회선마다 따로 적용되지 않고 결합 전체 금액으로 적용됩니다.', 'GENERAL'),
  (1373, '결합상품', '친구끼리도 결합할 수 있나요?', 'Ubot 친구 결합은 월정액 19,000원 이상인 친구 휴대폰 2회선이면 월 4,000원, 3회선이면 월 9,000원을 대표 회선에서 할인합니다.', 'GENERAL'),
  (1375, '결합상품', '1인 가구도 결합 할인을 받을 수 있나요?', '1인 가구도 같은 명의의 휴대폰과 워치(월 5,500원 할인), 휴대폰과 태블릿(월 9,900원 할인), 휴대폰과 Ubot 인터넷(월 3,300원~7,700원 할인)을 결합할 수 있습니다.', 'GENERAL'),
  (1376, '결합상품', '휴대폰과 인터넷을 함께 쓰면 할인되나요?', 'Ubot 홈 결합으로 휴대폰 1회선과 인터넷을 함께 쓰면 인터넷 속도 100Mbps는 월 3,300원, 500Mbps는 월 5,500원, 1Gbps는 월 7,700원이 휴대폰 요금에서 할인됩니다.', 'GENERAL'),
  (1377, '결합상품', '고가 요금제 두 회선이면 더 할인되는 결합이 있나요?', 'Ubot 프리미엄 듀오는 월정액 80,000원 이상인 휴대폰 2회선을 결합하면 월 15,000원을 할인합니다. 프리미엄 듀오에 가입한 회선은 다른 결합에 중복 가입할 수 없습니다.', 'GENERAL'),
  (1378, '결합상품', '청년끼리 결합할 수 있나요?', 'Ubot 청년 듀오는 만 19~34세의 청년 요금제 2회선을 결합하면 월 6,000원을 할인합니다. 청년 듀오 구성원 중 한 명이라도 청년 요금제를 벗어나면 결합 할인이 종료됩니다.', 'GENERAL'),
  (1379, '결합상품', '부모님이나 자녀와 함께 쓰는 결합이 있나요?', '시니어 요금제 부모님과 함께 쓰는 Ubot 부모님 함께는 월 5,000원을 할인합니다. 키즈 요금제 자녀와 법정대리인이 함께 쓰는 Ubot 우리아이 함께는 월 4,000원을 할인합니다.', 'GENERAL'),
  (1380, '결합상품', '오래 쓴 가족에게 주는 결합 혜택이 있나요?', 'Ubot 가족 오래함께는 대표 회선을 36개월 이상 연속 이용하고 휴대폰 2회선 이상을 결합하면 월 10,000원을 할인합니다.', 'GENERAL'),
  (1467, '보안/개인정보', '보이스피싱 피해를 막으려면 어떻게 해야 하나요?', '보이스피싱을 막으려면 통신사·은행·수사기관은 전화로 계좌 이체나 앱 설치를 요구하지 않는다는 점을 기억해 주세요. 보이스피싱이 의심되는 전화는 끊고 해당 기관의 공식 번호로 다시 확인해 주세요. Ubot 피싱가드(월 2,200원)에 가입하면 위험 링크와 의심 전화를 미리 알려 드립니다.', 'GENERAL'),
  (1483, '보안/개인정보', '자녀가 휴대폰을 안전하게 쓰게 하려면 어떻게 하나요?', 'Ubot 키즈가드(월 2,200원, 키즈 안심 요금제에는 포함)에 가입하면 보호자가 자녀 위치를 확인하고 유해 사이트를 차단할 수 있습니다. 자녀의 휴대폰 결제 차단과 데이터 차단도 함께 설정하면 안전하게 이용하는 데 도움이 됩니다.', 'GENERAL'),
  (1682, '결합상품', '가족 결합 회선 수별로 가족 전체 할인 금액은 얼마인가요?', 'Ubot 가족 결합 할인은 가족 전체에 대해 대표 회선에서 한 번에 차감됩니다. 가족 결합 2회선은 월 6,000원, 3회선은 월 15,000원, 4회선은 월 20,000원, 5회선은 월 24,000원이 할인됩니다.', 'GENERAL');

CREATE TEMP TABLE product_before_variant (variant_id BIGINT PRIMARY KEY, origin_faq_id BIGINT NOT NULL, question TEXT NOT NULL) ON COMMIT DROP;
INSERT INTO product_before_variant (variant_id, origin_faq_id, question) VALUES
  (561, 113, '5G 스탠다드에 약정 할인을 받으면 월 요금이 얼마인가요?'),
  (562, 113, '5G 스탠다드에 약정 할인을 적용하면 매달 얼마인가요?'),
  (563, 113, '선택약정을 받는 5G 스탠다드 월 요금이 궁금해요.'),
  (564, 113, '5G 스탠다드 요금제의 약정 할인 후 월 납부액을 알려주세요.'),
  (565, 113, '약정 할인을 하면 5G 스탠다드 한 달 요금은 얼마인가요?'),
  (936, 188, '문자는 요금제마다 몇 건까지 무료인가요?'),
  (940, 188, '가입한 요금제에서 무료 SMS가 몇 건인지 알고 싶어요.'),
  (1361, 273, '가족 위치 알림을 그만 쓰고 싶어요.'),
  (1362, 273, '가족 위치 알림 서비스를 중단하고 싶어요.'),
  (1363, 273, '가족 위치 알림을 해지하려면 어떻게 하나요?'),
  (1364, 273, '가족 위치 알림이 더는 오지 않게 하고 싶어요.'),
  (1365, 273, '이용 중인 가족 위치 알림을 끄고 싶어요.'),
  (1371, 275, '넘버플러스를 쓰면 요금제를 하나 더 가입해야 하나요?'),
  (1372, 275, '넘버플러스를 쓰려면 요금제에 추가로 가입해야 하나요?'),
  (1374, 275, '넘버플러스 때문에 요금제를 하나 더 써야 하나요?'),
  (1375, 275, '넘버플러스 가입 시 추가 회선 요금제도 가입해야 하나요?');

CREATE TEMP TABLE product_new_faq ON COMMIT DROP AS
SELECT * FROM seed_product_faq WHERE faq_id BETWEEN 1905 AND 2168;
CREATE TEMP TABLE product_new_variant ON COMMIT DROP AS
SELECT * FROM seed_product_variant WHERE origin_faq_id BETWEEN 1905 AND 2168;
-- 바꿀 FAQ·질문 표현의 변경 후 값은 기본 CSV 값이다.
CREATE TEMP TABLE product_update ON COMMIT DROP AS
SELECT s.faq_id, s.category, s.question, s.answer, s.intent,
       b.category AS before_category, b.question AS before_question, b.answer AS before_answer, b.intent AS before_intent
FROM product_before_faq b
JOIN seed_product_faq s ON s.faq_id = b.faq_id;
CREATE TEMP TABLE product_variant_update ON COMMIT DROP AS
SELECT s.variant_id, s.origin_faq_id, s.question, b.question AS before_question
FROM product_before_variant b
JOIN seed_product_variant s ON s.variant_id = b.variant_id AND s.origin_faq_id = b.origin_faq_id;

DO $$
BEGIN
  IF (SELECT count(*) FROM product_new_faq) <> 264
     OR EXISTS (SELECT 1 FROM product_new_faq WHERE intent <> 'PRODUCT_DATA')
     OR (SELECT count(*) FROM product_new_variant) <> 1320
     OR (SELECT count(*) FROM product_update) <> 76
     OR (SELECT count(*) FROM product_variant_update) <> 16 THEN
    RAISE EXCEPTION 'Product FAQ seed does not match the baseline CSV (expected 264 FAQs, 1320 variants, 76 FAQ and 16 variant updates)';
  END IF;
END $$;

-- 새 FAQ가 모두 같은 질문으로 있으면 이미 반영된 DB로 보고 건너뛴다.
SELECT (SELECT count(*) FROM faq f JOIN product_new_faq n ON n.faq_id = f.id AND n.question = f.question) <> 264
       AND NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'demo_product_faq_backup')
       AS demo_product_apply
\gset

\if :demo_product_apply
DO $$
DECLARE
  differing TEXT;
BEGIN
  IF (SELECT count(*) FROM users WHERE email = 'admin@admin.com' AND role = 'ADMIN' AND deleted_at IS NULL) <> 1 THEN
    RAISE EXCEPTION 'Exactly one active admin@admin.com ADMIN is required';
  END IF;
  SELECT string_agg(f.id::text, ', ' ORDER BY f.id) INTO differing FROM faq f JOIN product_new_faq n ON n.faq_id = f.id;
  IF differing IS NOT NULL THEN
    RAISE EXCEPTION 'FAQ IDs for the product FAQs are already used: %', differing;
  END IF;
  SELECT string_agg(v.id::text, ', ' ORDER BY v.id) INTO differing
  FROM faq_question_variants v JOIN product_new_variant n ON n.variant_id = v.id;
  IF differing IS NOT NULL THEN
    RAISE EXCEPTION 'Variant IDs for the product FAQs are already used: %', differing;
  END IF;
  SELECT string_agg(u.faq_id::text, ', ' ORDER BY u.faq_id) INTO differing
  FROM product_update u
  LEFT JOIN faq f ON f.id = u.faq_id AND f.deleted_at IS NULL
  LEFT JOIN faq_category c ON c.id = f.category_id
  WHERE f.id IS NULL OR c.name IS DISTINCT FROM u.before_category OR f.question IS DISTINCT FROM u.before_question
     OR f.answer IS DISTINCT FROM u.before_answer OR f.intent IS DISTINCT FROM u.before_intent;
  IF differing IS NOT NULL THEN
    RAISE EXCEPTION 'FAQs differ from the values before the product FAQs: %', differing;
  END IF;
  SELECT string_agg(u.variant_id::text, ', ' ORDER BY u.variant_id) INTO differing
  FROM product_variant_update u
  LEFT JOIN faq_question_variants v ON v.id = u.variant_id
  WHERE v.origin_faq_id IS DISTINCT FROM u.origin_faq_id OR v.question IS DISTINCT FROM u.before_question;
  IF differing IS NOT NULL THEN
    RAISE EXCEPTION 'Variants differ from the values before the product FAQs: %', differing;
  END IF;
END $$;

-- kind: CATEGORY_CREATED(id=카테고리), FAQ_INSERTED·FAQ_UPDATED(id=FAQ), VARIANT_UPDATED(id=질문 표현). 텍스트 칸은 변경 전 값이다.
CREATE TABLE demo_product_faq_backup (
    kind TEXT NOT NULL,
    id BIGINT NOT NULL,
    old_version INTEGER,
    applied_version INTEGER,
    category TEXT,
    question TEXT,
    answer TEXT,
    intent TEXT,
    PRIMARY KEY (kind, id)
);

WITH created AS (
    INSERT INTO faq_category (name)
    SELECT DISTINCT category FROM (SELECT category FROM product_new_faq UNION SELECT category FROM product_update) used
    ON CONFLICT (name) WHERE deleted_at IS NULL DO NOTHING
    RETURNING id
)
INSERT INTO demo_product_faq_backup (kind, id) SELECT 'CATEGORY_CREATED', id FROM created;

INSERT INTO faq (id, category_id, question, answer, intent, admin_id)
SELECT n.faq_id, c.id, n.question, n.answer, n.intent, u.user_id
FROM product_new_faq n
JOIN faq_category c ON c.name = n.category AND c.deleted_at IS NULL
CROSS JOIN users u
WHERE u.email = 'admin@admin.com' AND u.role = 'ADMIN' AND u.deleted_at IS NULL;
INSERT INTO faq_question_variants (id, origin_faq_id, question)
SELECT variant_id, origin_faq_id, question FROM product_new_variant ORDER BY variant_id;
INSERT INTO demo_product_faq_backup (kind, id) SELECT 'FAQ_INSERTED', faq_id FROM product_new_faq;
SELECT setval(pg_get_serial_sequence('faq', 'id'), (SELECT max(id) FROM faq), true) \g /dev/null
SELECT setval(pg_get_serial_sequence('faq_question_variants', 'id'), (SELECT max(id) FROM faq_question_variants), true) \g /dev/null

INSERT INTO demo_product_faq_backup (kind, id, old_version, applied_version, category, question, answer, intent)
SELECT 'FAQ_UPDATED', p.faq_id, f.version, f.version + 1, p.before_category, p.before_question, p.before_answer, p.before_intent
FROM product_update p
JOIN faq f ON f.id = p.faq_id;
INSERT INTO demo_product_faq_backup (kind, id, question)
SELECT 'VARIANT_UPDATED', variant_id, before_question FROM product_variant_update;

-- FaqService.updateActiveFaq(OldFaq.from)와 같은 이력을 남긴 뒤 FAQ를 바꾼다.
INSERT INTO old_faq (faq_id, version, category_id, question, answer, updated_at, updated_by, created_by, intent, cacheable)
SELECT f.id, f.version, f.category_id, f.question, f.answer, f.updated_at, u.user_id, f.admin_id, f.intent, f.cacheable
FROM product_update p
JOIN faq f ON f.id = p.faq_id
CROSS JOIN users u
WHERE u.email = 'admin@admin.com' AND u.role = 'ADMIN' AND u.deleted_at IS NULL;
UPDATE faq f
SET category_id = c.id, question = p.question, answer = p.answer, intent = p.intent,
    version = f.version + 1, updated_at = CURRENT_TIMESTAMP
FROM product_update p
JOIN faq_category c ON c.name = p.category AND c.deleted_at IS NULL
WHERE f.id = p.faq_id;
UPDATE faq_question_variants v
SET question = p.question
FROM product_variant_update p
WHERE v.id = p.variant_id;
-- 문장이 그대로인 질문 표현은 FaqService처럼 기존 벡터를 새 버전으로 옮겨, 바뀐 질문 표현만 백필을 기다린다.
UPDATE faq_embeddings e
SET faq_version = b.applied_version, updated_at = CURRENT_TIMESTAMP
FROM faq_question_variants v
JOIN demo_product_faq_backup b ON b.kind = 'FAQ_UPDATED' AND b.id = v.origin_faq_id
WHERE e.faq_question_variant_id = v.id
  AND e.vector_type = 'QUESTION'
  AND e.faq_version = b.old_version
  AND NOT EXISTS (SELECT 1 FROM product_variant_update p WHERE p.variant_id = v.id);
\echo 'Product FAQs applied: 264 inserted, 76 updated.'
\else
\echo 'Product FAQs are already present; skipped.'
\endif

\if :{?managed_transaction}
\else
COMMIT;
\endif
