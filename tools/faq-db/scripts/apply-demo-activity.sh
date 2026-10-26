#!/usr/bin/env bash
set -euo pipefail

# BE EC2에서 시연 데이터 세 가지를 운영 DB에 한 트랜잭션으로 순서대로 넣는다. apply-demo-activity.ps1과 같은 SQL을 쓴다.
#   1. seed-demo-product-faqs.sql (상품 의도 FAQ. 이미 있으면 건너뜀. 인기 FAQ 시연이 이 FAQ를 씀)
#   2. seed-demo-faqranking.sql   (인기 FAQ 시연)
#   3. seed-demo-telemap.sql      (FAQ 대시보드·통신 지도 시연)
# 배포 이미지가 이 스크립트와 시드 파일을 release 폴더에 함께 풀어 두므로, release 폴더에서 다음처럼 실행한다.
#   bash tools/faq-db/scripts/apply-demo-activity.sh [--force]
# DB 접속 정보는 release 폴더의 .env(POSTGRES_HOST·POSTGRES_PORT·POSTGRES_DB·POSTGRES_USER·POSTGRES_PASSWORD)에서 읽는다.
# 벡터는 넣지 않으므로 상품 FAQ를 새로 넣었으면 적용 뒤 임베딩을 백필한다.
# 시연 활동 초기화(clear-demo-activity.ps1)는 실제 질문 로그까지 모두 지우므로 운영 DB용으로 두지 않는다.

force=false
case "${1:-}" in
  '') ;;
  # question_log에 이미 행이 있어도 적용한다. 같은 시연 데이터가 중복으로 들어갈 수 있다.
  --force) force=true ;;
  *)
    echo 'usage: apply-demo-activity.sh [--force]' >&2
    exit 2
    ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
faq_db_dir="$(dirname "$script_dir")"
env_file="${ENV_FILE:-$(cd "$faq_db_dir/../.." && pwd)/.env}"
# 서버에 psql을 설치하지 않고 이 이미지로 실행한다. 로컬에서 시험할 때는 PSQL_DOCKER_NETWORK를 바꿀 수 있다.
psql_image="${PSQL_IMAGE:-postgres:18-alpine}"
docker_network="${PSQL_DOCKER_NETWORK:-host}"

seed_files=(
  "$faq_db_dir/seed/demo/seed-demo-product-faqs.sql"
  "$faq_db_dir/seed/demo/seed-demo-faqranking.sql"
  "$faq_db_dir/seed/demo/seed-demo-telemap.sql"
  # 상품 FAQ SQL이 새 FAQ·질문 표현을 기본 CSV에서 읽는다.
  "$faq_db_dir/seed/baseline/baseline-faqs.csv"
  "$faq_db_dir/seed/baseline/baseline-faq-variants.csv"
)
for file in "${seed_files[@]}"; do
  [ -f "$file" ] || { echo "Seed file is missing: $file" >&2; exit 1; }
done
[ -f "$env_file" ] || { echo "Env file is missing: $env_file" >&2; exit 1; }

# aws-be-deploy.sh처럼 .env를 source하지 않고 필요한 값만 읽는다.
env_value() { sed -n "s/^$1=//p" "$env_file" | tail -n 1; }
db_host="$(env_value POSTGRES_HOST)"
db_port="$(env_value POSTGRES_PORT)"
db_port="${db_port:-5432}"
db_name="$(env_value POSTGRES_DB)"
db_user="$(env_value POSTGRES_USER)"
db_password="$(env_value POSTGRES_PASSWORD)"
for name in db_host db_name db_user db_password; do
  [ -n "${!name}" ] || { echo "Missing PostgreSQL setting in $env_file: $name" >&2; exit 1; }
done

docker_cmd=(docker)
docker info >/dev/null 2>&1 || docker_cmd=(sudo docker)

# 시드 SQL은 /tmp의 파일을 읽으므로 임시 폴더를 컨테이너의 /tmp로 연결한다. 비밀번호는 명령줄에 남지 않게 파일로 넘긴다.
staging="$(mktemp -d)"
password_file="$(mktemp)"
trap 'rm -rf "$staging" "$password_file"' EXIT
cp "${seed_files[@]}" "$staging/"
cat > "$staging/apply-demo-activity.sql" <<'SQL'
BEGIN;
\i /tmp/seed-demo-product-faqs.sql
\i /tmp/seed-demo-faqranking.sql
\i /tmp/seed-demo-telemap.sql
COMMIT;
SQL
chmod -R a+rX "$staging"
printf 'PGPASSWORD=%s\n' "$db_password" > "$password_file"

run_psql() {
  "${docker_cmd[@]}" run --rm --network "$docker_network" --env-file "$password_file" -v "$staging:/tmp:ro" "$psql_image" \
    psql --no-psqlrc -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" "$@"
}

if [ "$force" != true ]; then
  existing="$(run_psql -At -c 'SELECT count(*) FROM question_log')"
  if [ "$existing" != 0 ]; then
    echo "question_log already has $existing rows, so the demo data would be added twice. Pass --force to apply anyway." >&2
    exit 1
  fi
fi

if ! run_psql -q -v managed_transaction=1 -f /tmp/apply-demo-activity.sql; then
  echo 'Demo activity seed import failed; all demo activity changes were rolled back.' >&2
  exit 1
fi

counts="$(run_psql -At -F '|' -c "SELECT (SELECT count(*) FROM question_log), (SELECT count(*) FROM faq_log),
  (SELECT count(*) FROM surging_alerts), (SELECT count(*) FROM faq WHERE intent = 'PRODUCT_DATA' AND deleted_at IS NULL)")"
IFS='|' read -r question_logs faq_logs alerts product_faqs <<< "$counts"
echo "Demo seeds applied: $question_logs question logs, $faq_logs FAQ logs, $alerts surging alerts, $product_faqs product FAQs."
echo 'Backfill FAQ embeddings if product FAQs were newly inserted.'
