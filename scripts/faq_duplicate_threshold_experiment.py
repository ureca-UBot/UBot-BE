"""Evaluate the 60 FAQ duplicate-suspicion settings on one frozen log snapshot.

First run exports pair_review.csv. Judge each unique FAQ pair as duplicate,
distinct, or uncertain, then rerun with the same --as-of timestamp.
"""

import argparse
import csv
import io
import subprocess
from collections import defaultdict
from datetime import datetime
from decimal import Decimal
from pathlib import Path


GAPS = [Decimal(i) / 100 for i in range(1, 11)]
RATES = [Decimal(i) / 100 for i in range(10, 61, 10)]
MIN_TOP1_SAMPLES = 10
EVENT_FIELDS = [
    "question_log_id", "question_created_at", "user_question", "sub_question",
    "user_text", "first_id", "first_version", "first_question", "first_answer",
    "first_similarity", "second_id", "second_version", "second_question",
    "second_answer", "second_similarity", "gap",
]
REVIEW_FIELDS = [
    "pair_id", "faq_a_id", "faq_b_id", "faq_a_version", "faq_a_question",
    "faq_a_answer", "faq_b_version", "faq_b_question", "faq_b_answer",
    "event_count", "lowest_gap", "sample_questions", "label", "reason",
]
SUMMARY_FIELDS = [
    "gap_threshold", "rate_threshold", "flagged_faq_count", "review_pair_count",
    "confirmed_duplicate_pair_count", "found_duplicate_rate", "unjudged_pair_count",
]
DETAIL_FIELDS = [
    "gap_threshold", "rate_threshold", "faq_id", "top1_sample_count",
    "low_gap_count", "low_gap_rate", "candidate_pair_count",
    "confirmed_duplicate_pair_count", "unjudged_pair_count",
]


def write_csv(path: Path, fields: list[str], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def export_events(as_of: str) -> list[dict[str, str]]:
    # datetime.fromisoformat validates as_of before interpolation.
    sql = f"""
        COPY (
          WITH current_version_logs AS (
            SELECT f.id AS faq_id, f.version, f.question, f.answer,
                   fl.question_log_id, fl.rank, fl.similarity,
                   ql.created_at AS question_created_at, ql.user_question,
                   ql.sub_question, ql.user_text
            FROM faq f
            JOIN faq_log fl ON fl.faq_id = f.id AND fl.rank IN (1, 2)
            JOIN question_log ql ON ql.id = fl.question_log_id
                AND ql.created_at >= f.updated_at
                AND ql.created_at < TIMESTAMP '{as_of}'
            WHERE f.deleted_at IS NULL
          )
          SELECT first_log.question_log_id, first_log.question_created_at,
                 first_log.user_question, first_log.sub_question,
                 first_log.user_text,
                 first_log.faq_id AS first_id, first_log.version AS first_version,
                 first_log.question AS first_question,
                 first_log.answer AS first_answer,
                 first_log.similarity AS first_similarity,
                 second_log.faq_id AS second_id, second_log.version AS second_version,
                 second_log.question AS second_question,
                 second_log.answer AS second_answer,
                 second_log.similarity AS second_similarity,
                 first_log.similarity - second_log.similarity AS gap
          FROM current_version_logs first_log
          JOIN current_version_logs second_log
            ON second_log.question_log_id = first_log.question_log_id
            AND second_log.rank = 2
          WHERE first_log.rank = 1 AND first_log.similarity IS NOT NULL
            AND second_log.similarity IS NOT NULL
          ORDER BY first_log.question_log_id
        ) TO STDOUT WITH CSV HEADER
    """
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-X", "-q",
         "-U", "ubot", "-d", "ubot", "-v", "ON_ERROR_STOP=1", "-c", sql],
        check=True, capture_output=True, text=True, encoding="utf-8",
    )
    return list(csv.DictReader(io.StringIO(result.stdout)))


def pair_key(row: dict[str, str]) -> tuple[int, int]:
    return tuple(sorted((int(row["first_id"]), int(row["second_id"]))))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--as-of", required=True, help="Fixed Asia/Seoul timestamp")
    parser.add_argument("--output-dir", type=Path, default=Path("build/faq-duplicate-eval"))
    parser.add_argument("--labels-file", type=Path,
                        help="Optional CSV with pair_id,label,reason for a repeatable review")
    args = parser.parse_args()
    as_of = datetime.fromisoformat(args.as_of).isoformat(sep=" ")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    events_path = args.output_dir / "pair_events.csv"
    snapshot_path = args.output_dir / "snapshot_as_of.txt"
    if events_path.exists():
        if not snapshot_path.exists() or snapshot_path.read_text(encoding="utf-8-sig").strip() != as_of:
            raise ValueError("Existing snapshot has a different or unknown as-of time")
        with events_path.open(encoding="utf-8-sig", newline="") as source:
            events = list(csv.DictReader(source))
    else:
        events = export_events(as_of)
        write_csv(events_path, EVENT_FIELDS, events)
        snapshot_path.write_text(as_of + "\n", encoding="utf-8")

    by_first = defaultdict(list)
    for row in events:
        by_first[int(row["first_id"])].append(row)
    eligible = {faq_id: rows for faq_id, rows in by_first.items()
                if len(rows) >= MIN_TOP1_SAMPLES}
    eligible_events = [row for rows in eligible.values() for row in rows]
    by_pair = defaultdict(list)
    for row in eligible_events:
        by_pair[pair_key(row)].append(row)

    review_path = args.output_dir / "pair_review.csv"
    previous = {}
    if review_path.exists():
        with review_path.open(encoding="utf-8-sig", newline="") as source:
            for row in csv.DictReader(source):
                previous[row["pair_id"]] = (row["label"], row["reason"])
    if args.labels_file:
        previous = {}
        with args.labels_file.open(encoding="utf-8-sig", newline="") as source:
            for row in csv.DictReader(source):
                previous[row["pair_id"]] = (row["label"], row.get("reason", ""))
        actual_ids = {f"{a}-{b}" for a, b in by_pair}
        if set(previous) != actual_ids:
            raise ValueError("Pair labels do not exactly match the eligible snapshot pairs")
    review = []
    labels = {}
    for (a, b), pair_rows in sorted(by_pair.items()):
        pair_id = f"{a}-{b}"
        first = pair_rows[0]
        a_side = "first" if int(first["first_id"]) == a else "second"
        b_side = "second" if a_side == "first" else "first"
        label, reason = previous.get(pair_id, ("", ""))
        if label not in ("", "duplicate", "distinct", "uncertain"):
            raise ValueError(f"Invalid label: {pair_id}: {label}")
        labels[(a, b)] = label
        samples = []
        for row in sorted(pair_rows, key=lambda item: Decimal(item["gap"])):
            question = row["user_question"] or row["sub_question"] or row["user_text"]
            if question and question not in samples:
                samples.append(question)
            if len(samples) == 3:
                break
        review.append({
            "pair_id": pair_id, "faq_a_id": a, "faq_b_id": b,
            "faq_a_version": first[f"{a_side}_version"],
            "faq_a_question": first[f"{a_side}_question"],
            "faq_a_answer": first[f"{a_side}_answer"],
            "faq_b_version": first[f"{b_side}_version"],
            "faq_b_question": first[f"{b_side}_question"],
            "faq_b_answer": first[f"{b_side}_answer"],
            "event_count": len(pair_rows),
            "lowest_gap": min(Decimal(row["gap"]) for row in pair_rows),
            "sample_questions": " | ".join(samples), "label": label,
            "reason": reason,
        })
    write_csv(review_path, REVIEW_FIELDS, review)

    total_duplicates = sum(label == "duplicate" for label in labels.values())
    summaries = []
    details = []
    for gap_threshold in GAPS:
        for rate_threshold in RATES:
            flagged = {}
            candidate_pairs = set()
            for faq_id, rows in eligible.items():
                close = [row for row in rows if Decimal(row["gap"]) < gap_threshold]
                rate = Decimal(len(close)) / len(rows)
                if rate < rate_threshold:
                    continue
                pairs = {pair_key(row) for row in close}
                candidate_pairs.update(pairs)
                flagged[faq_id] = (len(rows), len(close), rate, pairs)
            for faq_id, (count, close_count, rate, pairs) in flagged.items():
                details.append({
                    "gap_threshold": f"{gap_threshold:.2f}",
                    "rate_threshold": f"{rate_threshold:.2f}",
                    "faq_id": faq_id, "top1_sample_count": count,
                    "low_gap_count": close_count, "low_gap_rate": f"{rate:.6f}",
                    "candidate_pair_count": len(pairs),
                    "confirmed_duplicate_pair_count": sum(labels[p] == "duplicate" for p in pairs),
                    "unjudged_pair_count": sum(labels[p] not in ("duplicate", "distinct") for p in pairs),
                })
            found = sum(labels[p] == "duplicate" for p in candidate_pairs)
            summaries.append({
                "gap_threshold": f"{gap_threshold:.2f}",
                "rate_threshold": f"{rate_threshold:.2f}",
                "flagged_faq_count": len(flagged),
                "review_pair_count": len(candidate_pairs),
                "confirmed_duplicate_pair_count": found,
                "found_duplicate_rate": f"{found / total_duplicates:.6f}" if total_duplicates else "",
                "unjudged_pair_count": sum(labels[p] not in ("duplicate", "distinct") for p in candidate_pairs),
            })
    write_csv(args.output_dir / "threshold_summary.csv", SUMMARY_FIELDS, summaries)
    write_csv(args.output_dir / "faq_by_threshold.csv", DETAIL_FIELDS, details)
    print(f"snapshot={as_of}; pair_events={len(events)}; eligible_faqs={len(eligible)}; "
          f"review_pairs={len(review)}; judged={sum(label in ('duplicate', 'distinct') for label in labels.values())}; "
          f"duplicates={total_duplicates}; conditions={len(summaries)}")
    print(f"review={review_path}")
    print(f"summary={args.output_dir / 'threshold_summary.csv'}")


if __name__ == "__main__":
    main()
