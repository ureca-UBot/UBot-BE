"""Check that rewritten FAQ answers keep a sentence that passes the answer sentence filter.

The chat pipeline keeps only answer sentences whose embedding similarity to the user question is at or
above FAQ_SENTENCE_SIMILARITY_THRESHOLD (FaqSentenceFilterService). This script uses each variant of a FAQ
as the question and reports, per variant, the best answer sentence. A FAQ passes when every variant has
at least one sentence at or above the threshold. Nothing is written to the database.
"""

import argparse
import csv
import json
import math
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


SEED = Path(__file__).resolve().parent / "seed" / "baseline"
LIST_ITEM = re.compile(r"^\s*((?:[-*•]|\d+[.)]))(?:\s+(.*))?$")
# Approximates java.text.BreakIterator.getSentenceInstance(Locale.KOREAN) for Korean answers.
SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
BATCH_SIZE = 64


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as source:
        return list(csv.DictReader(source))


def manifest_ids(path: Path) -> list[str]:
    if not path.exists():
        return []
    return [str(faq_id) for faq_id in json.loads(path.read_text(encoding="utf-8"))["faqIds"]]


def segments(answer: str) -> list[tuple[bool, str]]:
    """Split an answer into (is_list, text) segments the same way FaqSentenceFilterService does."""
    result: list[tuple[bool, str]] = []
    content: list[str] = []
    is_list = False
    lines = re.split(r"\r\n|\r|\n", answer)

    def flush() -> None:
        text = " ".join(" ".join(content).split())
        if text:
            result.append((is_list, text))
        content.clear()

    for index, line in enumerate(lines):
        if not line.strip():
            following = next((later for later in lines[index + 1:] if later.strip()), None)
            if following is not None and LIST_ITEM.match(following):
                continue
            flush()
            is_list = False
            continue
        item = LIST_ITEM.match(line)
        if item:
            is_list = True
            if item.group(2) and item.group(2).strip():
                content.append(item.group(2).strip())
        else:
            if is_list and not line[0].isspace():
                flush()
                is_list = False
            content.append(line.strip())
    flush()
    return result


def sentences(answer: str) -> list[str]:
    result: list[str] = []
    for is_list, text in segments(answer):
        if is_list:
            result.append(text)
        else:
            result.extend(part.strip() for part in SENTENCE_END.split(text) if part.strip())
    return result


class Embedder:
    def __init__(self, engine: str, base_url: str, model: str, timeout: float) -> None:
        self.engine = engine
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout = timeout
        self.cache: dict[str, list[float]] = {}

    def embed(self, texts: list[str]) -> dict[str, list[float]]:
        missing = list(dict.fromkeys(text for text in texts if text not in self.cache))
        for start in range(0, len(missing), BATCH_SIZE):
            batch = missing[start:start + BATCH_SIZE]
            for text, vector in zip(batch, self._request(batch)):
                self.cache[text] = vector
        return {text: self.cache[text] for text in texts}

    def _request(self, texts: list[str]) -> list[list[float]]:
        if self.engine == "ollama":
            url = f"{self.base_url}/api/embed"
            body = {"model": self.model, "input": texts}
        else:
            url = f"{self.base_url}/embeddings"
            body = {"model": self.model, "input": texts}
        request = urllib.request.Request(
            url, data=json.dumps(body).encode("utf-8"), headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError) as error:
            raise SystemExit(f"Embedding request to {url} failed: {error}") from error
        if self.engine == "ollama":
            vectors = payload.get("embeddings")
        else:
            vectors = [item["embedding"] for item in sorted(payload.get("data", []), key=lambda item: item["index"])]
        if not vectors or len(vectors) != len(texts):
            raise SystemExit(f"Embedding response from {url} has {len(vectors or [])} vectors for {len(texts)} texts")
        return vectors


def cosine(left: list[float], right: list[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(a * a for a in left))
    right_norm = math.sqrt(sum(b * b for b in right))
    return float("nan") if left_norm == 0 or right_norm == 0 else dot / (left_norm * right_norm)


def check(faq: dict[str, str], variants: list[str], embedder: Embedder, threshold: float) -> dict[str, object]:
    answer_sentences = sentences(faq["answer"])
    vectors = embedder.embed(answer_sentences + variants)
    scores = [[cosine(vectors[variant], vectors[sentence]) for sentence in answer_sentences] for variant in variants]
    variant_results = []
    for variant, row in zip(variants, scores):
        best = max(range(len(row)), key=lambda index: row[index]) if row else None
        variant_results.append({
            "variant": variant,
            "best": row[best] if best is not None else float("nan"),
            "bestSentence": best,
            "passed": best is not None and row[best] >= threshold,
        })
    sentence_results = []
    for index, sentence in enumerate(answer_sentences):
        column = [row[index] for row in scores]
        sentence_results.append({
            "sentence": sentence,
            "passedVariants": sum(score >= threshold for score in column),
            "min": min(column),
            "max": max(column),
        })
    return {
        "faqId": faq["faqId"],
        "question": faq["question"],
        "passed": all(result["passed"] for result in variant_results),
        "variants": variant_results,
        "sentences": sentence_results,
    }


def print_result(result: dict[str, object], verbose: bool) -> None:
    status = "PASS" if result["passed"] else "FAIL"
    print(f"[{status}] FAQ {result['faqId']}: {result['question']}")
    if result["passed"] and not verbose:
        return
    for variant in result["variants"]:
        mark = "ok " if variant["passed"] else "LOW"
        sentence = variant["bestSentence"]
        label = f"S{sentence + 1}" if sentence is not None else "-"
        print(f"    {mark} {variant['best']:.3f} {label}  <- {variant['variant']}")
    total = len(result["variants"])
    for index, sentence in enumerate(result["sentences"]):
        print(f"    S{index + 1} {sentence['passedVariants']}/{total} variants "
              f"(min {sentence['min']:.3f}, max {sentence['max']:.3f}) {sentence['sentence']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ids", help="Comma-separated FAQ IDs to check")
    parser.add_argument("--seed-dir", type=Path, default=SEED)
    parser.add_argument("--threshold", type=float,
                        default=float(os.environ.get("FAQ_SENTENCE_SIMILARITY_THRESHOLD", "0.6")))
    parser.add_argument("--engine", choices=["ollama", "openai"], default="ollama",
                        help="ollama: /api/embed (AI_MODE=ollama), openai: OpenAI-compatible /embeddings (vLLM)")
    parser.add_argument("--base-url", help="Default: OLLAMA_BASE_URL or EMBEDDING_BASE_URL for the chosen engine")
    parser.add_argument("--model", help="Default: OLLAMA_EMBEDDING_MODEL or EMBEDDING_MODEL for the chosen engine")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--verbose", action="store_true", help="Print sentence scores for passing FAQs too")
    parser.add_argument("--json", action="store_true", help="Print the full result as JSON")
    args = parser.parse_args()

    if args.engine == "ollama":
        base_url = args.base_url or os.environ.get("OLLAMA_BASE_URL", "http://localhost:11435")
        model = args.model or os.environ.get("OLLAMA_EMBEDDING_MODEL", "bge-m3:567m")
    else:
        base_url = args.base_url or os.environ.get("EMBEDDING_BASE_URL", "http://localhost:8001/v1")
        model = args.model or os.environ.get("EMBEDDING_MODEL", "ubot-embedding")

    ids: list[str] = []
    if args.ids:
        ids += [faq_id.strip() for faq_id in args.ids.split(",") if faq_id.strip()]
    if not ids:
        parser.error("Pass --ids")
    deleted_path = args.seed_dir / "deleted-faqs.json"
    deleted = set(manifest_ids(deleted_path))
    ids = [faq_id for faq_id in dict.fromkeys(ids) if faq_id not in deleted]

    faqs = {faq["faqId"]: faq for faq in read_csv(args.seed_dir / "baseline-faqs.csv")}
    variants: dict[str, list[str]] = {}
    for variant in read_csv(args.seed_dir / "baseline-faq-variants.csv"):
        variants.setdefault(variant["originFaqId"], []).append(variant["question"])
    unknown = [faq_id for faq_id in ids if faq_id not in faqs]
    if unknown:
        raise SystemExit(f"FAQ IDs not in the seed CSV: {', '.join(unknown)}")

    embedder = Embedder(args.engine, base_url, model, args.timeout)
    results = [check(faqs[faq_id], variants.get(faq_id, []), embedder, args.threshold) for faq_id in ids]
    failed = [result for result in results if not result["passed"]]
    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
    else:
        print(f"Sentence gate: threshold {args.threshold}, {args.engine} {model} at {base_url}")
        for result in results:
            print_result(result, args.verbose)
        print(f"{len(results) - len(failed)}/{len(results)} FAQs passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
