"""Embed manually written FAQ variants and write one resumable seed CSV part."""

import argparse
import csv
import json
import os
import urllib.request
from pathlib import Path


SEED_DIR = Path(__file__).resolve().parents[1] / "src/main/resources/seed"
MODEL = "bge-m3:567m"
FIELDS = ("originFaqId", "variantId", "question", "vector")


def read_faqs(part: int) -> list[dict[str, str]]:
    with (SEED_DIR / f"baseline-faqs-{part}.csv").open(encoding="utf-8-sig", newline="") as file:
        rows = list(csv.DictReader(file))
    if len(rows) != 200 or [int(row["faqId"]) for row in rows] != list(range((part - 1) * 200 + 1, part * 200 + 1)):
        raise ValueError(f"FAQ IDs or row count are wrong in part {part}")
    return rows


def read_drafts(part: int) -> dict[int, list[str]]:
    drafts: dict[int, list[str]] = {}
    path = SEED_DIR / f"variant-drafts-{part}.txt"
    with path.open(encoding="utf-8") as file:
        for line_number, line in enumerate(file, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            fields = line.split("|")
            if len(fields) != 5:
                raise ValueError(f"{path.name}:{line_number}: expected FAQ ID and four expressions")
            faq_id = int(fields[0])
            if faq_id in drafts:
                raise ValueError(f"Duplicate FAQ ID {faq_id}")
            drafts[faq_id] = [expression.strip() for expression in fields[1:]]
    return drafts


def build_rows(faqs: list[dict[str, str]], drafts: dict[int, list[str]]) -> list[dict[str, str]]:
    expected_ids = {int(faq["faqId"]) for faq in faqs}
    if set(drafts) != expected_ids:
        raise ValueError(f"Missing FAQ IDs: {sorted(expected_ids - set(drafts))}; extra IDs: {sorted(set(drafts) - expected_ids)}")
    result = []
    for faq in faqs:
        faq_id = int(faq["faqId"])
        questions = [faq["question"], *drafts[faq_id]]
        if any(not question or len(question) > 1000 for question in questions):
            raise ValueError(f"Blank or overlong expression for FAQ {faq_id}")
        if len({" ".join(question.split()) for question in questions}) != 5:
            raise ValueError(f"Repeated expression for FAQ {faq_id}")
        for index, question in enumerate(questions):
            result.append({
                "originFaqId": str(faq_id),
                "variantId": str((faq_id - 1) * 5 + index + 1),
                "question": question,
                "answer": faq["answer"],
            })
    return result


def embed(texts: list[str], url: str) -> list[list[float]]:
    payload = {
        "model": MODEL,
        "input": texts,
        "options": {"num_ctx": 4096},
        "keep_alive": "30m",
    }
    request = urllib.request.Request(
        url.rstrip("/") + "/api/embed",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        vectors = json.load(response)["embeddings"]
    if len(vectors) != len(texts) or any(len(vector) != 1024 for vector in vectors):
        raise ValueError("Embedding response count or dimensions differ from the request")
    return vectors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--part", type=int, choices=range(1, 6), required=True)
    parser.add_argument("--url", default="http://localhost:11435")
    parser.add_argument("--batch-size", type=int, default=20)
    args = parser.parse_args()

    rows = build_rows(read_faqs(args.part), read_drafts(args.part))
    output = SEED_DIR / f"baseline-faq-variants-{args.part}.csv"
    partial = output.with_suffix(".partial.csv")
    done = []
    if partial.exists():
        with partial.open(encoding="utf-8-sig", newline="") as file:
            done = list(csv.DictReader(file))
        if len(done) > len(rows) or any(
            done[index]["variantId"] != row["variantId"]
            or done[index]["originFaqId"] != row["originFaqId"]
            or done[index]["question"] != row["question"]
            for index, row in enumerate(rows[: len(done)])
        ):
            raise ValueError("Partial CSV does not match current FAQ variant text")

    print(f"part={args.part} variants={len(rows)} already_embedded={len(done)}", flush=True)
    with partial.open("a", encoding="utf-8-sig" if not partial.exists() else "utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDS, lineterminator="\n")
        if file.tell() == 0:
            writer.writeheader()
        for offset in range(len(done), len(rows), args.batch_size):
            batch = rows[offset : offset + args.batch_size]
            texts = [f"질문: {row['question']}\n답변: {row['answer']}" for row in batch]
            vectors = embed(texts, args.url)
            for row, vector in zip(batch, vectors):
                writer.writerow({
                    "originFaqId": row["originFaqId"],
                    "variantId": row["variantId"],
                    "question": row["question"],
                    "vector": json.dumps(vector, separators=(",", ":")),
                })
            file.flush()
            print(f"part={args.part} embedded={offset + len(batch)}/{len(rows)}", flush=True)

    os.replace(partial, output)


if __name__ == "__main__":
    main()
