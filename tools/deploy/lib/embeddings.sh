#!/usr/bin/env bash
# 지금 Embedding Profile의 FAQ 벡터를 확인하고, 모자라면 임시 백엔드로 백필합니다.
# 정하는 값: BACKFILL_AFTER_SWITCH
# 쓰는 값: COMPOSE, EMBEDDING_ENGINE, EMBEDDING_PROFILE_VERSION


run_psql() {
  "${COMPOSE[@]}" exec -T postgres \
    psql \
    -U "$(read_env POSTGRES_USER)" \
    -d "$(read_env POSTGRES_DB)" \
    -v ON_ERROR_STOP=1 \
    -At \
    "$@"
}


# 삭제되지 않은 FAQ가 모두 지금 Embedding Profile의 벡터를 가지고 있는지 확인한다.
# 반환값: 0 모두 있음(FAQ가 없는 경우 포함), 1 모자람, 2 확인 실패
# 조건문 안에서 호출하면 set -e가 적용되지 않으므로, 조회 결과를 직접 검사한다.
check_faq_embedding_coverage() {
  local provider
  local model
  local faq_table
  local embedding_tables
  local profile_id
  local active_count
  local covered_count

  case "${EMBEDDING_ENGINE}" in
    ollama)
      provider="ollama"
      model="$(read_env OLLAMA_EMBEDDING_MODEL)"
      ;;

    vllm)
      provider="openai-compatible"
      model="$(read_env EMBEDDING_MODEL)"
      ;;

    *)
      echo "ERROR: unsupported embedding engine: ${EMBEDDING_ENGINE}"
      return 2
      ;;
  esac

  # 첫 배포에서는 아직 테이블이 없다. 채울 FAQ도 없으므로 통과한다.
  faq_table="$(
    run_psql -c "SELECT to_regclass('public.faq') IS NOT NULL;" </dev/null \
      || true
  )"

  case "${faq_table}" in
    f)
      echo "FAQ table does not exist yet. Nothing to backfill."
      return 0
      ;;

    t)
      ;;

    *)
      echo "ERROR: could not query the database"
      return 2
      ;;
  esac

  active_count="$(
    run_psql -c "SELECT COUNT(*) FROM faq WHERE deleted_at IS NULL;" </dev/null \
      || true
  )"

  if ! [[ "${active_count}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: could not count active FAQs"
    return 2
  fi

  echo "Active FAQ count: ${active_count}"

  # FAQ가 없으면 백필할 것도 없음.
  if [ "${active_count}" -eq 0 ]; then
    echo "No active FAQs. Embedding coverage check passed."
    return 0
  fi

  embedding_tables="$(
    run_psql -c "
      SELECT to_regclass('public.embedding_profiles') IS NOT NULL
         AND to_regclass('public.faq_embeddings') IS NOT NULL;
    " </dev/null \
      || true
  )"

  if [ "${embedding_tables}" != "t" ]; then
    echo "Embedding profile tables do not exist yet."
    return 1
  fi

  profile_id="$(
    run_psql \
      -v provider="${provider}" \
      -v model="${model}" \
      -v profile_version="${EMBEDDING_PROFILE_VERSION}" \
      <<'SQL' || true
SELECT profile_id
FROM embedding_profiles
WHERE provider = :'provider'
  AND model_name = :'model'
  AND dimensions = 1024
  AND profile_version = :'profile_version';
SQL
  )"

  if [ -z "${profile_id}" ]; then
    echo "Embedding profile does not exist yet: provider=${provider}, model=${model}, profileVersion=${EMBEDDING_PROFILE_VERSION}"
    return 1
  fi

  if ! [[ "${profile_id}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: could not read the embedding profile"
    return 2
  fi

  covered_count="$(
    run_psql \
      -v profile_id="${profile_id}" \
      <<'SQL' || true
SELECT COUNT(*)
FROM faq f
JOIN faq_embeddings fe
  ON fe.faq_id = f.id
WHERE f.deleted_at IS NULL
  AND fe.profile_id = :'profile_id'
  AND fe.vector_type = 'QUESTION'
  AND fe.faq_version = f.version;
SQL
  )"

  if ! [[ "${covered_count}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: could not count FAQ embeddings"
    return 2
  fi

  echo "Current FAQ embedding count: ${covered_count}/${active_count}"

  if [ "${covered_count}" -ne "${active_count}" ]; then
    echo "Missing FAQ embeddings: $((active_count - covered_count))"
    return 1
  fi

  echo "FAQ embedding coverage check passed."
  return 0
}


# 새 이미지로 백필만 하고 끝나는 임시 백엔드를 실행한다.
# compose run으로 띄운 컨테이너는 backend 서비스 이름으로 잡히지 않아 nginx 트래픽을 받지 않는다.
# 종료 코드: 0 완료, 3 적용되지 않은 마이그레이션이 있어 건너뜀, 그 밖은 실패
run_embedding_backfill() {
  local exit_code=0

  "${COMPOSE[@]}" \
    run --rm \
    -T \
    backend \
    --app.embedding-backfill.run=true \
    </dev/null \
    || exit_code=$?

  return "${exit_code}"
}


BACKFILL_AFTER_SWITCH=false


# 백엔드를 교체하기 전에 지금 Embedding Profile의 FAQ 벡터를 준비한다.
# 여기서 실패하면 기존 백엔드와 nginx는 그대로 남는다.
prepare_embeddings_before_switch() {
  echo "== Check FAQ embeddings before switch =="

  "${COMPOSE[@]}" \
    up -d \
    --wait \
    postgres

  local coverage=0
  local backfill=0

  check_faq_embedding_coverage || coverage=$?

  if [ "${coverage}" -eq 0 ]; then
    return 0
  fi

  if [ "${coverage}" -ne 1 ]; then
    echo "ERROR: could not check FAQ embedding coverage"
    exit 1
  fi

  echo "== Backfill embeddings with a one-off backend =="

  run_embedding_backfill || backfill=$?

  case "${backfill}" in
    0)
      coverage=0
      check_faq_embedding_coverage || coverage=$?

      if [ "${coverage}" -ne 0 ]; then
        echo "ERROR: embedding backfill finished but FAQ embeddings are still missing"
        echo "The running backend was not changed."
        exit 1
      fi
      ;;

    3)
      # 임시 백엔드는 스키마를 바꾸지 않는다. 새 스키마가 필요하면 교체를 먼저 하고 그 뒤에 백필한다.
      echo "Pending migrations found. Embeddings will be backfilled right after the backend switch."
      BACKFILL_AFTER_SWITCH=true
      ;;

    *)
      echo "ERROR: embedding backfill failed (exit code ${backfill})"
      echo "The running backend was not changed."
      exit 1
      ;;
  esac
}


finish_embeddings_after_switch() {
  local coverage=0
  local backfill=0

  if [ "${BACKFILL_AFTER_SWITCH}" = "true" ]; then
    echo "== Backfill embeddings after switch =="

    run_embedding_backfill || backfill=$?

    if [ "${backfill}" -ne 0 ]; then
      echo "ERROR: embedding backfill failed after the switch (exit code ${backfill})"
      echo "The new backend is running, but FAQ search returns no results until the backfill succeeds."
      echo "Run tools/backfill-embeddings.ps1 or retry the deployment."
      exit 1
    fi

    check_faq_embedding_coverage || coverage=$?

    if [ "${coverage}" -ne 0 ]; then
      echo "ERROR: FAQ embeddings are still missing after the backfill"
      echo "Run tools/backfill-embeddings.ps1 or retry the deployment."
      exit 1
    fi

    return 0
  fi

  echo "== Check FAQ embeddings after switch =="

  check_faq_embedding_coverage || coverage=$?

  # 교체 전 확인과 교체 사이에 등록되거나 수정된 FAQ가 있을 수 있다. 배포는 실패로 처리하지 않는다.
  if [ "${coverage}" -ne 0 ]; then
    echo "WARNING: some FAQs have no vector for the current embedding profile."
    echo "Run tools/backfill-embeddings.ps1 to fill them."
  fi
}
