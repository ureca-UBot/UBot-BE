"""Compare LOW_RAG_QUALITY thresholds against one fixed FAQ log snapshot.

The review CSV stays under build/ (ignored by Git). Fill `label` with
`related`, `unrelated`, or `uncertain`, then rerun with the same --as-of value.
"""

import argparse
import csv
import io
import subprocess
from collections import defaultdict
from datetime import datetime
from decimal import Decimal
from pathlib import Path


REVIEW_FIELDS = [
    "faq_id", "faq_version", "faq_question", "faq_answer", "question_log_id",
    "user_question", "sub_question", "user_text", "rank", "similarity",
    "question_created_at", "label", "reason",
]


def export_rows(as_of: str, max_threshold: Decimal) -> list[dict[str, str]]:
    # as_of is parsed by datetime.fromisoformat before interpolation.
    sql = f"""
        COPY (
          WITH current_version_logs AS (
            SELECT f.id AS faq_id, f.version AS faq_version,
                   f.question AS faq_question, f.answer AS faq_answer,
                   fl.question_log_id, fl.rank, fl.similarity,
                   ql.user_question, ql.sub_question, ql.user_text,
                   ql.created_at AS question_created_at
            FROM faq f
            JOIN faq_log fl ON fl.faq_id = f.id AND fl.rank <= 3
            JOIN question_log ql ON ql.id = fl.question_log_id
                             AND ql.created_at >= f.updated_at
                             AND ql.created_at < TIMESTAMP '{as_of}'
            WHERE f.deleted_at IS NULL
          ), candidates AS (
            SELECT faq_id FROM current_version_logs
            GROUP BY faq_id
            HAVING COUNT(*) >= 10 AND AVG(similarity) < {max_threshold}
          )
          SELECT logs.* FROM current_version_logs logs
          JOIN candidates USING (faq_id)
          ORDER BY logs.faq_id, logs.question_created_at, logs.question_log_id
        ) TO STDOUT WITH CSV HEADER
    """
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-X", "-q",
         "-U", "ubot", "-d", "ubot", "-v", "ON_ERROR_STOP=1", "-c", sql],
        check=True, capture_output=True, text=True, encoding="utf-8",
    )
    return list(csv.DictReader(io.StringIO(result.stdout)))


def write_csv(path: Path, fields: list[str], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--as-of", required=True, help="Fixed Asia/Seoul timestamp")
    parser.add_argument("--output-dir", type=Path, default=Path("build/faq-threshold-eval"))
    parser.add_argument("--start", type=Decimal, default=Decimal("0.71"))
    parser.add_argument("--end", type=Decimal, default=Decimal("0.80"))
    parser.add_argument("--step", type=Decimal, default=Decimal("0.01"))
    args = parser.parse_args()
    if not (Decimal("0") < args.start <= args.end <= Decimal("1")) or args.step <= 0:
        parser.error("Require 0 < start <= end <= 1 and step > 0")
    interval_count = (args.end - args.start) / args.step
    if interval_count != int(interval_count):
        parser.error("The step must divide the start-to-end interval exactly")
    thresholds = [args.start + args.step * index for index in range(int(interval_count) + 1)]
    as_of = datetime.fromisoformat(args.as_of).isoformat(sep=" ")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    review_path = args.output_dir / "review.csv"

    previous_labels = {}
    if review_path.exists():
        with review_path.open(encoding="utf-8-sig", newline="") as source:
            for row in csv.DictReader(source):
                previous_labels[(row["faq_id"], row["question_log_id"])] = (
                    row.get("label", ""), row.get("reason", "")
                )

    rows = export_rows(as_of, args.end)
    for row in rows:
        row["label"], row["reason"] = previous_labels.get(
            (row["faq_id"], row["question_log_id"]), ("", "")
        )
        if row["label"] not in ("", "related", "unrelated", "uncertain"):
            raise ValueError(f"Invalid label for FAQ {row['faq_id']}, log {row['question_log_id']}")
    write_csv(review_path, REVIEW_FIELDS, rows)

    by_faq = defaultdict(list)
    for row in rows:
        by_faq[row["faq_id"]].append(row)

    details = []
    summaries = []
    for threshold in thresholds:
        flagged = []
        for faq_id, faq_rows in by_faq.items():
            average = sum(Decimal(row["similarity"]) for row in faq_rows) / len(faq_rows)
            if average >= threshold:
                continue
            judged = [row for row in faq_rows if row["label"] in ("related", "unrelated")]
            unrelated = sum(row["label"] == "unrelated" for row in judged)
            flagged.append((faq_id, faq_rows, average, judged, unrelated))
            details.append({
                "threshold": f"{threshold:.3f}", "faq_id": faq_id,
                "faq_version": faq_rows[0]["faq_version"],
                "top_k_count": len(faq_rows), "average_similarity": f"{average:.6f}",
                "judged_count": len(judged), "unrelated_count": unrelated,
                "not_related_rate": f"{unrelated / len(faq_rows):.6f}",
                "unjudged_count": len(faq_rows) - len(judged),
            })
        judged_total = sum(len(item[3]) for item in flagged)
        unrelated_total = sum(item[4] for item in flagged)
        faq_rates = [item[4] / len(item[1]) for item in flagged]
        link_total = sum(len(item[1]) for item in flagged)
        summaries.append({
            "threshold": f"{threshold:.3f}", "flagged_faq_count": len(flagged),
            "linked_question_count": link_total,
            "judged_count": judged_total, "unrelated_count": unrelated_total,
            "mean_faq_not_related_rate": f"{sum(faq_rates) / len(faq_rates):.6f}" if faq_rates else "",
            "overall_not_related_rate": f"{unrelated_total / link_total:.6f}" if link_total else "",
            "unjudged_count": link_total - judged_total,
        })

    write_csv(args.output_dir / "faq_by_threshold.csv", [
        "threshold", "faq_id", "faq_version", "top_k_count", "average_similarity",
        "judged_count", "unrelated_count", "not_related_rate", "unjudged_count",
    ], details)
    write_csv(args.output_dir / "threshold_summary.csv", [
        "threshold", "flagged_faq_count", "linked_question_count", "judged_count",
        "unrelated_count", "mean_faq_not_related_rate", "overall_not_related_rate",
        "unjudged_count",
    ], summaries)
    print(f"snapshot={as_of}; candidate_faqs={len(by_faq)}; question_links={len(rows)}")
    for item in summaries:
        print(f"{item['threshold']}: {item['flagged_faq_count']} FAQs, "
              f"{item['linked_question_count']} links, {item['judged_count']} judged")
    print(f"review={review_path}")


if __name__ == "__main__":
    main()
