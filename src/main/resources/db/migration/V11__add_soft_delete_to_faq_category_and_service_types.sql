ALTER TABLE faq_category ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE faq_category DROP CONSTRAINT faq_category_name_key;
CREATE UNIQUE INDEX uq_faq_category_name_active ON faq_category (name) WHERE deleted_at IS NULL;

ALTER TABLE service_types ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE service_types DROP CONSTRAINT service_types_service_code_key;
CREATE UNIQUE INDEX uq_service_types_service_code_active ON service_types (service_code) WHERE deleted_at IS NULL;
