"""FAQ CSV의 지정 컬럼을 임베딩해 vector 컬럼을 생성하거나 갱신한다."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import tempfile
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen


DEFAULT_MODEL = "bge-m3:567m"
DEFAULT_OLLAMA_URL = "http://localhost:11435"
DEFAULT_DIMENSIONS = 1024
BATCH_SIZE = 32


def embed_batch(ollama_url: str, model: str, inputs: list[str]) -> list[list[float]]:
    request_body = json.dumps(
        {
            "model": model,
            "input": inputs,
            "options": {"num_ctx": 4096},
            "keep_alive": "30m",
        }
    ).encode("utf-8")
    request = Request(
        f"{ollama_url.rstrip('/')}/api/embed",
        data=request_body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=120) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except URLError as error:
        raise RuntimeError(f"Ollama 임베딩 요청에 실패했습니다: {error}") from error

    vectors = payload.get("embeddings")
    if not isinstance(vectors, list) or len(vectors) != len(inputs):
        raise RuntimeError("Ollama가 요청한 입력 수와 다른 임베딩 결과를 반환했습니다.")
    return vectors


def validate_vectors(vectors: list[list[float]], dimensions: int) -> None:
    for vector in vectors:
        if (
            not isinstance(vector, list)
            or len(vector) != dimensions
            or any(not isinstance(value, (int, float)) or not math.isfinite(value) for value in vector)
        ):
            raise RuntimeError(f"Ollama가 {dimensions}차원이 아닌 임베딩을 반환했습니다.")


def read_rows(path: Path, input_columns: list[str]) -> tuple[list[str], list[dict[str, str]]]:
    with path.open(encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None:
            raise ValueError("CSV 헤더가 없습니다.")
        headers = reader.fieldnames
        missing = [column for column in input_columns if column not in headers]
        if missing:
            raise ValueError(f"CSV에 임베딩 입력 컬럼이 없습니다: {', '.join(missing)}")
        rows = list(reader)
    if not rows:
        raise ValueError("벡터를 생성할 FAQ 행이 없습니다.")
    for line_number, row in enumerate(rows, start=2):
        if any(not row[column].strip() for column in input_columns):
            raise ValueError(f"{line_number}번째 행의 임베딩 입력값이 비어 있습니다.")
    return headers, rows


def create_input(row: dict[str, str], input_columns: list[str], separator: str) -> str:
    return separator.join(row[column].strip() for column in input_columns)


def write_rows(path: Path, headers: list[str], rows: list[dict[str, str]]) -> str:
    output_headers = headers if "vector" in headers else [*headers, "vector"]
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", newline="", dir=path.parent, delete=False
    ) as temporary:
        writer = csv.DictWriter(temporary, fieldnames=output_headers, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
        temporary_path = Path(temporary.name)
    os.replace(temporary_path, path)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_metadata(path: Path, model: str, dimensions: int, input_columns: list[str], separator: str,
                   faq_count: int, checksum: str) -> None:
    metadata = {
        "embeddingModel": model,
        "dimensions": dimensions,
        "inputColumns": input_columns,
        "inputSeparator": separator,
        "faqCount": faq_count,
        "sha256": checksum,
    }
    path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def validate_existing_vectors(rows: list[dict[str, str]], dimensions: int) -> None:
    for line_number, row in enumerate(rows, start=2):
        try:
            vector = json.loads(row["vector"])
        except (KeyError, json.JSONDecodeError) as error:
            raise ValueError(f"{line_number}번째 행의 vector 값이 올바른 JSON 배열이 아닙니다.") from error
        validate_vectors([vector], dimensions)


def main() -> None:
    parser = argparse.ArgumentParser(description="FAQ CSV의 임베딩 벡터를 생성합니다.")
    parser.add_argument("--source", type=Path, default=Path("src/main/resources/seed/baseline-faqs.csv"))
    parser.add_argument("--output", type=Path, help="생성 대상 CSV 경로. 생략하면 원본 CSV를 갱신합니다.")
    parser.add_argument("--metadata", type=Path, default=Path("src/main/resources/seed/baseline-faqs.metadata.json"))
    parser.add_argument("--ollama-url", default=DEFAULT_OLLAMA_URL)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--dimensions", type=int, default=DEFAULT_DIMENSIONS)
    parser.add_argument("--input-columns", nargs="+", default=["question"],
                        help="임베딩에 사용할 CSV 컬럼. 예: --input-columns question answer")
    parser.add_argument("--input-separator", default="\n\n",
                        help="여러 입력 컬럼을 결합할 때 사용할 문자열")
    parser.add_argument("--reuse-existing-vectors", action="store_true",
                        help="기존 vector 컬럼을 검증하고 메타데이터만 다시 작성합니다")
    arguments = parser.parse_args()
    if arguments.dimensions <= 0:
        raise ValueError("임베딩 차원은 1 이상이어야 합니다.")

    output = arguments.output or arguments.source
    headers, rows = read_rows(arguments.source, arguments.input_columns)
    if arguments.reuse_existing_vectors:
        if "vector" not in headers:
            raise ValueError("재사용할 vector 컬럼이 없습니다.")
        if output != arguments.source:
            raise ValueError("기존 벡터 재사용 시 출력 경로는 원본 CSV와 같아야 합니다.")
        validate_existing_vectors(rows, arguments.dimensions)
        checksum = hashlib.sha256(output.read_bytes()).hexdigest()
        write_metadata(
            arguments.metadata,
            arguments.model,
            arguments.dimensions,
            arguments.input_columns,
            arguments.input_separator,
            len(rows),
            checksum,
        )
        print(f"기존 FAQ 벡터를 검증했습니다: {output}")
        print(f"메타데이터를 저장했습니다: {arguments.metadata}")
        return

    for start in range(0, len(rows), BATCH_SIZE):
        batch = rows[start : start + BATCH_SIZE]
        inputs = [create_input(row, arguments.input_columns, arguments.input_separator) for row in batch]
        vectors = embed_batch(arguments.ollama_url, arguments.model, inputs)
        validate_vectors(vectors, arguments.dimensions)
        for row, vector in zip(batch, vectors, strict=True):
            row["vector"] = json.dumps(vector, ensure_ascii=False, separators=(",", ":"))
        print(f"임베딩 생성: {min(start + len(batch), len(rows))}/{len(rows)}", flush=True)

    checksum = write_rows(output, headers, rows)
    write_metadata(
        arguments.metadata,
        arguments.model,
        arguments.dimensions,
        arguments.input_columns,
        arguments.input_separator,
        len(rows),
        checksum,
    )
    print(f"벡터가 포함된 FAQ CSV를 저장했습니다: {output}")
    print(f"메타데이터를 저장했습니다: {arguments.metadata}")


if __name__ == "__main__":
    main()
