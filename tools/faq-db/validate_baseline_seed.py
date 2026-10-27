"""Validate the FAQ and variant seed CSVs."""

import argparse
import csv
import json
from pathlib import Path


SEED = Path(__file__).resolve().parent / "seed" / "baseline"
FAQ_FIELDS = ["faqId", "category", "question", "answer", "intent"]
VARIANT_FIELDS = ["originFaqId", "variantId", "question"]
# FAQ categories that ProductIntentHandler maps to a product search tool (ProductType).
PRODUCT_CATEGORIES = {"요금제", "요금제/변경", "부가서비스", "결합상품", "해외 로밍", "로밍/통신망 선택"}


def rows(path: Path, fields: list[str]) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != fields:
            raise ValueError(f"{path.name}: expected columns {fields}, got {reader.fieldnames}")
        result = list(reader)
        if any(None in row or any(value is None for value in row.values()) for row in result):
            raise ValueError(f"{path.name}: row does not match CSV columns")
        return result


def validate(seed: Path) -> dict[str, object]:
    faqs = rows(seed / "baseline-faqs.csv", FAQ_FIELDS)
    variants = rows(seed / "baseline-faq-variants.csv", VARIANT_FIELDS)
    if not faqs:
        raise ValueError("Expected at least one FAQ")
    categories: set[str] = set()
    variant_offset = 0
    for offset, faq in enumerate(faqs):
        faq_id = offset + 1
        if int(faq["faqId"]) != faq_id or any(not faq[field].strip() for field in FAQ_FIELDS[1:]):
            raise ValueError(f"Invalid FAQ {faq_id}")
        if faq["intent"] == "PRODUCT_DATA" and faq["category"] not in PRODUCT_CATEGORIES:
            raise ValueError(f"PRODUCT_DATA FAQ {faq_id} has unmapped category {faq['category']}")
        categories.add(faq["category"])
        questions_for_faq: set[str] = set()
        while variant_offset < len(variants) and int(variants[variant_offset]["originFaqId"]) == faq_id:
            variant = variants[variant_offset]
            variant_id = variant_offset + 1
            if int(variant["variantId"]) != variant_id or int(variant["originFaqId"]) != faq_id:
                raise ValueError(f"Invalid variant ID {variant_id}")
            question = variant["question"]
            normalized = " ".join(question.split())
            if not normalized or len(question) > 1000 or (not questions_for_faq and question != faq["question"]):
                raise ValueError(f"Invalid question for variant {variant_id}")
            if normalized in questions_for_faq:
                raise ValueError(f"Duplicate variant question for FAQ {faq_id}")
            questions_for_faq.add(normalized)
            variant_offset += 1
        if not questions_for_faq:
            raise ValueError(f"FAQ {faq_id} has no variants")
    if variant_offset != len(variants):
        raise ValueError(f"Unexpected variant for FAQ {variants[variant_offset]['originFaqId']}")
    deleted = deleted_faq_ids(seed / "deleted-faqs.json")
    unknown = [faq_id for faq_id in deleted if not 1 <= faq_id <= len(faqs)]
    if unknown:
        raise ValueError(f"deleted-faqs.json: FAQ IDs not in the CSV: {unknown}")
    return {"faqCount": len(faqs), "variantCount": len(variants), "categoryCount": len(categories),
            "deletedCount": len(deleted)}


def deleted_faq_ids(path: Path) -> list[int]:
    if not path.exists():
        return []
    values = json.loads(path.read_text(encoding="utf-8"))["faqIds"]
    if any(not str(value).isdigit() for value in values) or len(set(map(str, values))) != len(values):
        raise ValueError(f"{path.name}: FAQ IDs must be unique numbers")
    return [int(value) for value in values]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed-dir", type=Path, default=SEED)
    parser.add_argument("--json", action="store_true", help="Print counts for reset scripts")
    args = parser.parse_args()
    summary = validate(args.seed_dir)
    if args.json:
        print(json.dumps(summary, separators=(",", ":")))
    else:
        print(f"All {summary['faqCount']} FAQs and {summary['variantCount']} variants validated")


if __name__ == "__main__":
    main()
