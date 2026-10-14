# Baseline FAQ seed progress

- Source: `baseline-faqs.csv` has 1,000 FAQ rows, explicit `faqId` 1–1000, and no vector column.
- Split FAQ files: `baseline-faqs-1.csv` through `baseline-faqs-5.csv` contain 200 rows each.
- Variant text: FAQ 1–1000 complete in `variant-drafts-1.txt` through `variant-drafts-5.txt`. Each FAQ has its original question plus four manually written alternatives.
- Variant vectors: `baseline-faq-variants-1.csv` through `baseline-faq-variants-5.csv` complete (5,000 `QUESTION_ANSWER` vectors, 1,024 dimensions).
- Seed scripts: `tools/reset-local-db.ps1` and `tools/seed-baseline.sql` updated. On 2026-10-14 the local reset completed and verified 1,000 FAQs, 5,000 variants, 5,000 embeddings.
- Three-table reset: `tools/reset-faq-tables.ps1` and `tools/seed-faq-tables.sql` added and executed successfully on 2026-10-14. It replaces only FAQ, variant, and embedding rows and aborts when other tables reference FAQs.

The FAQ row order and IDs must not change after variant drafting begins.
