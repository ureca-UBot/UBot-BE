"""Turn a dated additional FAQ draft into numbered, embedded seed CSV pairs."""

import argparse
import csv
import json
import math
import re
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "src/main/resources/seed"
FAQ_FIELDS = ["faqId", "category", "question", "answer", "intent"]
VARIANT_FIELDS = ["originFaqId", "variantId", "question", "vector"]
MODEL = "bge-m3:567m"


def read_csv(path: Path, fields: list[str]) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != fields:
            raise ValueError(f"Unexpected columns in {path.name}: {reader.fieldnames}")
        return list(reader)


def write_csv(path: Path, fields: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def normalize(value: str) -> str:
    return " ".join(value.split())


def parse_source(path: Path) -> list[dict[str, object]]:
    sections: list[list[str]] = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.startswith("### **후보"):
            sections.append([])
        if sections:
            sections[-1].append(line.strip())
    if not sections:
        raise ValueError(f"No FAQ sections in {path}")
    result = []
    for number, lines in enumerate(sections, 1):
        fields: dict[str, str] = {}
        expressions: dict[int, str] = {}
        for index, line in enumerate(lines):
            match = re.fullmatch(r"- 유사표현 (\d+):\s*(.+)", line)
            if match:
                expressions[int(match.group(1))] = match.group(2).strip()
            match = re.fullmatch(r"(\d+)\.\s*(.+)", line)
            if match:
                expressions[int(match.group(1))] = match.group(2).strip()
            for label, key in (
                ("새 FAQ 질문", "question"),
                ("새 FAQ 답변 초안", "answer"),
                ("새 FAQ 답변", "answer"),
                ("의도", "intent"),
                ("카테고리", "category"),
            ):
                inline = re.fullmatch(r"- " + re.escape(label) + r":\s*(.+)", line)
                headed = re.fullmatch(r"\*\*" + re.escape(label) + r":?\*\*\s*(.*)", line)
                if inline or headed:
                    value = inline.group(1) if inline else headed.group(1)
                    if not value:
                        value = next((item for item in lines[index + 1 :] if item), "")
                    if key in fields:
                        raise ValueError(f"FAQ {number}: repeated {key}")
                    fields[key] = value.strip()
        if set(fields) != {"question", "answer", "intent", "category"}:
            raise ValueError(f"FAQ {number}: missing fields {sorted({'question', 'answer', 'intent', 'category'} - set(fields))}")
        if any(not value for value in fields.values()):
            raise ValueError(f"FAQ {number}: blank field")
        if not all(index in expressions for index in range(1, 5)):
            raise ValueError(f"FAQ {number}: four similar expressions required")
        questions = [fields["question"], *(expressions[index] for index in range(1, 5))]
        if len({normalize(question) for question in questions}) != 5:
            raise ValueError(f"FAQ {number}: duplicate expression")
        result.append({**fields, "questions": questions})
    return result


def embed(texts: list[str], url: str) -> list[list[float]]:
    payload = {"model": MODEL, "input": texts, "options": {"num_ctx": 4096}, "keep_alive": "30m"}
    request = urllib.request.Request(
        url.rstrip("/") + "/api/embed",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        vectors = json.load(response)["embeddings"]
    if len(vectors) != len(texts) or any(
        len(vector) != 1024 or any(not isinstance(x, (int, float)) or not math.isfinite(x) for x in vector)
        for vector in vectors
    ):
        raise ValueError("Invalid embedding response")
    return vectors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("date", help="Source filename stem, for example 1015")
    parser.add_argument("--url", default="http://localhost:11434")
    parser.add_argument("--batch-size", type=int, default=20)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]{4,8}", args.date):
        parser.error("date must be 4-8 digits")
    if args.batch_size < 1:
        parser.error("batch size must be positive")
    source = ROOT / "docs/additional-faq" / f"{args.date}.txt"
    if not source.exists():
        raise FileNotFoundError(source)
    faqs = parse_source(source)

    numbered = sorted(int(match.group(1)) for file in SEED.glob("baseline-faqs-*.csv")
                      if (match := re.fullmatch(r"baseline-faqs-(\d+)\.csv", file.name)))
    existing_faqs = {part: read_csv(SEED / f"baseline-faqs-{part}.csv", FAQ_FIELDS) for part in numbered}
    existing_variants = {part: read_csv(SEED / f"baseline-faq-variants-{part}.csv", VARIANT_FIELDS) for part in numbered}
    if any(len(existing_variants[part]) != 5 * len(existing_faqs[part]) for part in numbered):
        raise ValueError("An existing FAQ/variant pair is incomplete")
    old_questions = {normalize(row["question"]) for rows in existing_faqs.values() for row in rows}
    new_questions = [normalize(faq["question"]) for faq in faqs]
    if len(set(new_questions)) != len(new_questions) or any(question in old_questions for question in new_questions):
        raise ValueError("Source contains a duplicate FAQ question in itself or numbered seed files")
    next_faq_id = max((int(row["faqId"]) for rows in existing_faqs.values() for row in rows), default=0) + 1
    next_variant_id = max((int(row["variantId"]) for rows in existing_variants.values() for row in rows), default=0) + 1
    part = max(6, max(numbered, default=5))
    planned: list[tuple[int, dict[str, str], list[dict[str, str]], str]] = []
    for faq in faqs:
        if len(existing_faqs.get(part, [])) + sum(item[0] == part for item in planned) >= 200:
            part += 1
        faq_row = {"faqId": str(next_faq_id), **{key: str(faq[key]) for key in ("category", "question", "answer", "intent")}}
        variant_rows = []
        for question in faq["questions"]:
            variant_rows.append({"originFaqId": str(next_faq_id), "variantId": str(next_variant_id), "question": question, "vector": ""})
            next_variant_id += 1
        planned.append((part, faq_row, variant_rows, str(faq["answer"])))
        next_faq_id += 1

    days = SEED / "days"
    days.mkdir(exist_ok=True)
    staging = days / f"additional-faq-{args.date}.partial.json"
    fingerprints = [{"faqId": row["faqId"], "questions": [v["question"] for v in variants], "answer": answer}
                    for _, row, variants, answer in planned]
    if staging.exists():
        state = json.loads(staging.read_text(encoding="utf-8"))
        if state["fingerprints"] != fingerprints:
            raise ValueError("Partial embeddings differ from current source")
        vectors = state["vectors"]
    else:
        vectors = []
    if len(vectors) > len(planned) * 5:
        raise ValueError("Too many staged embeddings")
    texts = [f"질문: {variant['question']}\n답변: {answer}"
             for _, _, variants, answer in planned for variant in variants]
    for offset in range(len(vectors), len(texts), args.batch_size):
        vectors.extend(embed(texts[offset:offset + args.batch_size], args.url))
        staging.write_text(json.dumps({"fingerprints": fingerprints, "vectors": vectors}, ensure_ascii=False), encoding="utf-8")
        print(f"embedded {len(vectors)}/{len(texts)}", flush=True)

    affected = sorted({item[0] for item in planned})
    position = 0
    for part in affected:
        faq_rows = list(existing_faqs.get(part, []))
        variant_rows = list(existing_variants.get(part, []))
        for planned_part, faq_row, variants, _ in planned:
            if planned_part != part:
                continue
            faq_rows.append(faq_row)
            for variant in variants:
                variant["vector"] = json.dumps(vectors[position], separators=(",", ":"))
                variant_rows.append(variant)
                position += 1
        if len(faq_rows) > 200 or len(variant_rows) > 1000:
            raise ValueError(f"part {part}: exceeded CSV capacity")
        write_csv(SEED / f"baseline-faqs-{part}.csv", FAQ_FIELDS, faq_rows)
        write_csv(SEED / f"baseline-faq-variants-{part}.csv", VARIANT_FIELDS, variant_rows)
    manifest = {"date": args.date, "faqIds": [row["faqId"] for _, row, _, _ in planned], "parts": affected}
    (days / f"additional-faq-{args.date}.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    staging.unlink(missing_ok=True)
    print(f"prepared {len(planned)} FAQs and {len(texts)} variants in parts {affected}")


if __name__ == "__main__":
    main()
