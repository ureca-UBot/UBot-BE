"""Validate every numbered FAQ and variant seed CSV pair."""

import argparse
import csv
import json
import math
import re
from pathlib import Path


SEED = Path(__file__).resolve().parent / "seed"
FAQ_FIELDS = ["faqId", "category", "question", "answer", "intent"]
VARIANT_FIELDS = ["originFaqId", "variantId", "question", "vector"]
FAQ_NAME = re.compile(r"baseline-faqs-(\d+)\.csv")
VARIANT_NAME = re.compile(r"baseline-faq-variants-(\d+)\.csv")


def rows(path: Path, fields: list[str]) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != fields:
            raise ValueError(f"{path.name}: expected columns {fields}, got {reader.fieldnames}")
        return list(reader)


def discover(seed: Path) -> list[int]:
    faq_parts = {int(match.group(1)) for path in seed.iterdir()
                 if path.is_file() and (match := FAQ_NAME.fullmatch(path.name))}
    variant_parts = {int(match.group(1)) for path in seed.iterdir()
                     if path.is_file() and (match := VARIANT_NAME.fullmatch(path.name))}
    if not faq_parts or faq_parts != variant_parts:
        raise ValueError(f"FAQ/variant file pairs differ: FAQ={sorted(faq_parts)}, variants={sorted(variant_parts)}")
    parts = sorted(faq_parts)
    if parts != list(range(1, parts[-1] + 1)):
        raise ValueError(f"Numbered file parts must be contiguous from 1: {parts}")
    return parts


def validate(seed: Path) -> dict[str, object]:
    parts = discover(seed)
    faq_total = 0
    variant_total = 0
    categories: set[str] = set()
    part_counts = []
    for part in parts:
        faqs = rows(seed / f"baseline-faqs-{part}.csv", FAQ_FIELDS)
        variants = rows(seed / f"baseline-faq-variants-{part}.csv", VARIANT_FIELDS)
        if not faqs or len(faqs) > 200 or len(variants) != 5 * len(faqs):
            raise ValueError(f"part {part}: expected 1-200 FAQs and five variants per FAQ")
        if len(variants) > 1000 or (part != parts[-1] and len(faqs) != 200):
            raise ValueError(f"part {part}: nonfinal pair must be full; each pair is limited to 200/1000")
        for offset, faq in enumerate(faqs):
            faq_id = faq_total + offset + 1
            if int(faq["faqId"]) != faq_id or any(not faq[field].strip() for field in FAQ_FIELDS[1:]):
                raise ValueError(f"part {part}: invalid FAQ {faq_id}")
            categories.add(faq["category"])
            questions_for_faq: set[str] = set()
            for index, variant in enumerate(variants[offset * 5: offset * 5 + 5]):
                variant_id = variant_total + offset * 5 + index + 1
                if int(variant["variantId"]) != variant_id or int(variant["originFaqId"]) != faq_id:
                    raise ValueError(f"part {part}: invalid variant ID {variant_id}")
                question = variant["question"]
                normalized = " ".join(question.split())
                if not normalized or len(question) > 1000 or (index == 0 and question != faq["question"]):
                    raise ValueError(f"part {part}: invalid question for variant {variant_id}")
                if normalized in questions_for_faq:
                    raise ValueError(f"part {part}: duplicate variant question for FAQ {faq_id}")
                questions_for_faq.add(normalized)
                vector = json.loads(variant["vector"])
                if not isinstance(vector, list) or len(vector) != 1024 or any(
                    isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                    for value in vector
                ):
                    raise ValueError(f"part {part}: invalid vector for variant {variant_id}")
        faq_total += len(faqs)
        variant_total += len(variants)
        part_counts.append({"part": part, "faqs": len(faqs), "variants": len(variants)})
    return {"parts": parts, "faqCount": faq_total, "variantCount": variant_total,
            "categoryCount": len(categories), "partCounts": part_counts}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed-dir", type=Path, default=SEED)
    parser.add_argument("--json", action="store_true", help="Print counts for reset scripts")
    args = parser.parse_args()
    summary = validate(args.seed_dir)
    if args.json:
        print(json.dumps(summary, separators=(",", ":")))
    else:
        for count in summary["partCounts"]:
            print(f"part {count['part']}: {count['faqs']} FAQs, {count['variants']} variants, vectors OK")
        print(f"All {summary['faqCount']} FAQs and {summary['variantCount']} variants validated")


if __name__ == "__main__":
    main()
