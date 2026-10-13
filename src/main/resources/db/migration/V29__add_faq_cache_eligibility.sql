-- Administrator approval defaults to false. This migration approves no FAQs.
ALTER TABLE faq ADD COLUMN cacheable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE old_faq ADD COLUMN cacheable BOOLEAN NOT NULL DEFAULT FALSE;
