DELETE FROM store_services;
DELETE FROM stores;
DELETE FROM service_types;

INSERT INTO stores (
    store_id,
    store_name,
    sido,
    sigungu,
    address,
    phone_number,
    longitude,
    latitude
) VALUES
    (1, '강남역점', '서울특별시', '강남구', '서울특별시 강남구 강남대로 396', '02-0000-0001', 127.0286, 37.4987),
    (2, '역삼역점', '서울특별시', '강남구', '서울특별시 강남구 테헤란로 156', '02-0000-0002', 127.0300, 37.5000),
    (3, '부산역점', '부산광역시', '동구', '부산광역시 동구 중앙대로 206', '051-0000-0003', 129.0403, 35.1151);

INSERT INTO stores (
    store_id,
    store_name,
    sido,
    sigungu,
    address,
    longitude,
    latitude,
    is_active,
    deleted_at
) VALUES
    (4, '비활성매장', '제주특별자치도', '제주시', '제주특별자치도 제주시 비활성로 1', 126.5312, 33.4996, FALSE, NULL),
    (5, '삭제매장', '대전광역시', '서구', '대전광역시 서구 삭제로 1', 127.3845, 36.3504, TRUE, CURRENT_TIMESTAMP);

INSERT INTO service_types (
    service_type_id,
    service_code,
    service_name,
    is_active
) VALUES
    (1, 'IDENTITY_THEFT_REPORT', '명의도용 접수', TRUE),
    (2, 'APPLE_AS', '애플 A/S', TRUE),
    (3, 'FOREIGN_LANGUAGE_SUPPORT', '외국어 지원', TRUE),
    (4, 'INACTIVE_SERVICE', '비활성 서비스', FALSE);

INSERT INTO store_services (store_id, service_type_id)
VALUES
    (1, 2),
    (1, 3),
    (2, 2);
