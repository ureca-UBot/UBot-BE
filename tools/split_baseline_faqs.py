"""Assign stable FAQ IDs and write five 200-row baseline seed files."""

import csv
from pathlib import Path


SEED_DIR = Path(__file__).resolve().parents[1] / "src/main/resources/seed"
SOURCE = SEED_DIR / "baseline-faqs.csv"
FIELDS = ("faqId", "category", "question", "answer", "intent")


def write_rows(path: Path, rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    with SOURCE.open(encoding="utf-8-sig", newline="") as source:
        original_rows = list(csv.DictReader(source))
    if len(original_rows) != 1000:
        raise ValueError(f"Expected 1000 FAQs, found {len(original_rows)}")

    rows = []
    for faq_id, original in enumerate(original_rows, 1):
        if any(not original[field].strip() for field in FIELDS[1:]):
            raise ValueError(f"Incomplete FAQ at row {faq_id}")
        rows.append({"faqId": faq_id, **{field: original[field] for field in FIELDS[1:]}})

    write_rows(SOURCE, rows)
    for part in range(5):
        write_rows(SEED_DIR / f"baseline-faqs-{part + 1}.csv", rows[part * 200 : (part + 1) * 200])


if __name__ == "__main__":
    main()
