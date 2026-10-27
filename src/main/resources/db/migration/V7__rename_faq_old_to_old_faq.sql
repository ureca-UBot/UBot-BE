ALTER TABLE faq_old RENAME TO old_faq;

ALTER TABLE old_faq RENAME COLUMN update_admin_id TO updated_by;
ALTER TABLE old_faq RENAME COLUMN create_admin_id TO created_by;

ALTER TABLE old_faq RENAME CONSTRAINT faq_old_pkey TO old_faq_pkey;
ALTER TABLE old_faq RENAME CONSTRAINT faq_old_faq_id_fkey TO old_faq_faq_id_fkey;
ALTER TABLE old_faq RENAME CONSTRAINT faq_old_category_id_fkey TO old_faq_category_id_fkey;
