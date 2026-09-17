DROP TABLE IF EXISTS store_services;
DROP TABLE IF EXISTS service_types;
DROP TABLE IF EXISTS stores;

CREATE TABLE stores (
    store_id BIGINT PRIMARY KEY,
    store_name VARCHAR(150) NOT NULL,
    sido VARCHAR(50),
    sigungu VARCHAR(50),
    address VARCHAR(500) NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    location geography(Point, 4326)
        GENERATED ALWAYS AS (
            ST_SetSRID(
                ST_MakePoint(
                    longitude::double precision,
                    latitude::double precision
                ),
                4326
            )::geography
        ) STORED,
    phone_number VARCHAR(30),
    business_hours TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_stores_location ON stores USING GIST (location);

CREATE TABLE service_types (
    service_type_id BIGINT PRIMARY KEY,
    service_code VARCHAR(50) NOT NULL UNIQUE,
    service_name VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE store_services (
    store_id BIGINT NOT NULL REFERENCES stores (store_id),
    service_type_id BIGINT NOT NULL REFERENCES service_types (service_type_id),
    PRIMARY KEY (store_id, service_type_id)
);

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

INSERT INTO service_types (
    service_type_id,
    service_code,
    service_name
) VALUES
    (1, 'IDENTITY_THEFT_REPORT', '명의도용 접수'),
    (2, 'APPLE_AS', '애플 A/S'),
    (3, 'FOREIGN_LANGUAGE_SUPPORT', '외국어 지원');

INSERT INTO store_services (store_id, service_type_id)
VALUES (1, 2);
