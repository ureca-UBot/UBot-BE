"""Validate the five FAQ and variant CSV pairs before resetting local data."""

import csv
import json
from pathlib import Path


SEED = Path(__file__).resolve().parents[1] / "src/main/resources/seed"


def rows(path: Path, fields: list[str]) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != fields:
            raise ValueError(f"{path.name}: expected columns {fields}, got {reader.fieldnames}")
        return list(reader)


def main() -> None:
    for part in range(1, 6):
        faqs = rows(SEED / f"baseline-faqs-{part}.csv", ["faqId", "category", "question", "answer", "intent"])
        variants = rows(SEED / f"baseline-faq-variants-{part}.csv", ["originFaqId", "variantId", "question", "vector"])
        if len(faqs) != 200 or len(variants) != 1000:
            raise ValueError(f"part {part}: expected 200 FAQs and 1000 variants")
        for offset, faq in enumerate(faqs):
            faq_id = (part - 1) * 200 + offset + 1
            if int(faq["faqId"]) != faq_id or any(not faq[field].strip() for field in ("category", "question", "answer", "intent")):
                raise ValueError(f"part {part}: invalid FAQ {faq_id}")
            questions_for_faq: set[str] = set()
            for index, variant in enumerate(variants[offset * 5 : offset * 5 + 5]):
                expected_id = (faq_id - 1) * 5 + index + 1
                if int(variant["variantId"]) != expected_id or int(variant["originFaqId"]) != faq_id:
                    raise ValueError(f"part {part}: invalid variant ID {expected_id}")
                question = variant["question"]
                if not question.strip() or len(question) > 1000 or (index == 0 and question != faq["question"]):
                    raise ValueError(f"part {part}: invalid question for variant {expected_id}")
                if question in questions_for_faq:
                    raise ValueError(f"part {part}: duplicate variant question {expected_id}: {question}")
                questions_for_faq.add(question)
                vector = json.loads(variant["vector"])
                if len(vector) != 1024 or not all(isinstance(value, (float, int)) for value in vector):
                    raise ValueError(f"part {part}: invalid vector for variant {expected_id}")
        print(f"part {part}: 200 FAQs, 1000 unique variants, 1024-dimensional vectors OK")
    source = rows(SEED / "baseline-faqs.csv", ["faqId", "category", "question", "answer", "intent"])
    if len(source) != 1000:
        raise ValueError("baseline-faqs.csv must contain 1000 FAQs")
    for part in range(1, 6):
        split = rows(SEED / f"baseline-faqs-{part}.csv", ["faqId", "category", "question", "answer", "intent"])
        if split != source[(part - 1) * 200 : part * 200]:
            raise ValueError(f"FAQ split {part} differs from baseline-faqs.csv")
    print("All 1000 FAQs and 5000 variants validated")


if __name__ == "__main__":
    main()
