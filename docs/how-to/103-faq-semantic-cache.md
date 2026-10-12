# 공식 FAQ 시맨틱 캐시 사용 안내

관리자가 승인한 일반 안내 FAQ에 등록 질문 표현을 연결하고, 사용자 질문이 조건을 통과하면 공식 답변을 직접 반환해요. 추가 추론 모델은 사용하지 않아요.

**기본값은 OFF예요.** 기존 실험에서 의도·조건이 다른 질문을 통과시킨 사례가 있어요. 일부 질문이 잘 동작한다는 결과만으로 전체 질문에 안전하다고 판단하지 않아요.

## 1. 설정

환경변수 전체 설명은 [설정 안내](../reference/configuration.md)에 있어요.

```properties
FAQ_CACHE_ENABLED=false
FAQ_CACHE_TTL=7d
FAQ_CACHE_SEMANTIC_ENABLED=false
FAQ_CACHE_REVIEWED_POLICY_ENABLED=false
FAQ_CACHE_MIN_SIMILARITY=0.95
FAQ_CACHE_MIN_MARGIN=0.15
FAQ_CACHE_EMBEDDING_MODEL=bge-m3:567m
```

캐시를 사용하려면 `FAQ_CACHE_ENABLED`와 `FAQ_CACHE_SEMANTIC_ENABLED`를 true로 설정하고 백엔드를 다시 시작해요. 검토한 다섯 FAQ의 질문 범위·고정 질문/답변·FAQ별 기준을 쓰려면 `FAQ_CACHE_REVIEWED_POLICY_ENABLED=true`로 설정해요. 이 정책의 대상은 FAQ 3·6·11·14·16이며, 임의의 FAQ 전체에 적용되는 의도 판별기는 아니에요.

검토 정책이 꺼져 있으면 공통 유사도와 경쟁 FAQ 점수 차이를 사용해요. 모델 이름은 실제 임베딩 클라이언트의 모델 이름과 같아야 해요. provider·모델·차원·프로필 버전이 달라지면 벡터를 공유하지 않으며 기준을 다시 검증해야 해요.

## 2. 처리 흐름

1. 질문 원문을 현재 임베딩 프로필로 한 번 임베딩해요.
2. 기존 FAQ Top 3와 시맨틱 후보 20개를 검색해요. 동일 FAQ의 여러 표현은 최고 점수로 묶고, 승인되지 않은 FAQ도 경쟁 후보로 남겨요.
3. 승인된 활성 GENERAL FAQ인지, 등록 표현과 공식 내용·버전·프로필이 일치하는지, 질문 범위·유사도·경쟁 점수 차이가 기준을 통과하는지 확인해요.
4. 통과한 FAQ는 DB의 최신 승인과 질문·답변·버전을 다시 확인해요. Redis에 저장된 답변이 있어도 DB 검증을 생략하지 않아요.
5. 검증된 공식 답변을 반환해요. Redis 키는 `faq:answer:{id}:v{version}`이에요. Redis에 없거나 Redis 접근이 실패하면 검증된 DB 답변을 사용해요.

검색 실패·모델 불일치·판정 거절·DB 검증 실패면 공식 답변을 생략하고 기존 처리 경로를 사용해요. 기존 FAQ의 근거 기준은 Top 3 / 0.75예요. 기준 미달 질문은 LLM을 호출하지 않고 미응답으로 끝날 수 있어요. 사용자 intent를 지정한 재검색에는 캐시를 적용하지 않아요.

## 3. DB 준비

| 마이그레이션 | 역할 |
|---|---|
| V28 | FAQ와 수정 이력에 `cacheable` 추가. 기본 false |
| V29 | 임베딩 프로필별 등록 표현, 고정 공식 내용·버전 저장 |

마이그레이션은 FAQ 승인이나 표현 등록을 자동으로 하지 않아요. FAQ와 현재 프로필의 질문 벡터는 [로컬 데이터 안내](local-data.md)와 [임베딩 백필 안내](llm-module.md#백필)를 참고해 준비해요.

**이전 실험 DB에 이 브랜치를 그대로 연결하면 안 돼요.** 이전 실험은 V25/V26을 캐시에 사용했고, 최신 develop은 같은 번호를 legacy 벡터 제거·가입 정보 테이블에 사용해요. 별도 DB에 최신 마이그레이션을 적용해야 해요. 기존 DB 이력을 repair하거나 체크섬을 바꿔서 맞추지 않아요.

## 4. 관리자 승인

`POST /admin/faqs`, `PATCH /admin/faqs`의 `cacheable` 필드로 승인해요.

- 생성 시 생략하면 false예요.
- 수정 시 생략하면 기존 GENERAL FAQ의 승인 상태를 유지해요.
- intent를 USER_DATA/STORE_DATA로 바꾸면 생략된 승인은 해제돼요.
- GENERAL이 아닌 FAQ에 true를 명시하면 `FAQ-007`로 거절해요.
- 승인 변경은 수정 이력에 남고 FAQ 버전이 올라가요.

PATCH에는 id·categoryId·question·answer·intent 등 기존 필수 필드도 보내야 해요. FAQ 내용·버전이 바뀌면 과거 표현은 검색에서 제외돼요. 다시 검토해 등록해야 해요.

## 5. 표현 등록

`scripts/semantic-cache/`는 백엔드가 호출하는 런타임 구성 요소가 아니라 수동 등록·검증 도구예요.

| 파일 | 용도 |
|---|---|
| `catalog-reviewed.json` | 대표 질문 5개와 추가 표현 44개. DB의 ID와 공식 내용이 일치하는지 검토 필요 |
| `register-reviewed-expressions.py` | 현재 프로필로 표현을 임베딩하고 DB에 등록 |
| `test-registration.py` | 별도 임시 DB·가짜 임베딩 서버를 사용하는 등록 도구 테스트 |

아래 컨테이너 이름과 프로필 ID를 사용할 DB의 실제 값으로 바꿔요. 예시의 `TEST_DB`도 실제 DB 이름으로 바꿔요.

```text
python -X utf8 scripts/semantic-cache/register-reviewed-expressions.py --container POSTGRES_CONTAINER --database TEST_DB --profile-id PROFILE_ID
```

기본 실행은 승인·공식 내용·현재 프로필 벡터를 검증하고 끝나요. 임베딩이나 DB 쓰기를 수행하지 않아요. 검토 후 등록할 때만 `--apply`를 추가해요.

```text
python -X utf8 scripts/semantic-cache/register-reviewed-expressions.py --container POSTGRES_CONTAINER --database TEST_DB --profile-id PROFILE_ID --apply
```

Ollama 기본 주소는 `http://localhost:11435`, OpenAI-compatible 기본 주소는 `http://localhost:8001/v1`이에요. 다른 서버는 `--embedding-url`로 지정해요. Python 표준 라이브러리와 Docker CLI를 사용해요.

도구는 임베딩 후 트랜잭션에서 FAQ 승인·내용·버전·벡터를 다시 확인해요. 중간에 바뀌면 등록을 취소해요. 같은 프로필과 표현을 다시 등록해도 중복 행을 만들지 않아요. 승인 상태는 변경하지 않아요. 등록 이후 서버는 DB를 읽으므로 도구를 함께 실행할 필요가 없어요.

## 6. 확인 방법

전체 테스트·빌드:

```text
gradlew.bat test build --no-daemon
```

등록 도구의 별도 테스트:

```text
python -X utf8 scripts/semantic-cache/test-registration.py
```

전체 테스트의 DB는 Testcontainers가 새로 만들어요. 실제 모델 서버를 사용하는 선택 테스트는 해당 서버를 준비했을 때 따로 실행해요. 자동 테스트 통과가 실제 모델 응답 품질을 보장하지는 않아요.

실제 API는 `POST /chat/questions`에 다음 JSON을 보내요. 서버 포트는 실행한 서버의 포트로 맞춰요.

```json
{"question":"esim이 뭐야?"}
```

응답 JSON에는 캐시 여부 필드가 없으므로 서버의 해당 `attemptId` 로그를 확인해요.

- `source=FAQ_OFFICIAL`: 공식 답변 반환, LLM 생략.
- `source=LLM`: 기존 LLM 경로 선택. 호출 성공 여부는 최종 응답도 확인해요.
- `source=INSUFFICIENT_FAQ`: 기존 검색 근거 부족.
- Redis `MISS/DB`도 공식 답변을 반환했다면 LLM을 생략한 경우예요.

전체 응답 시간은 HTTP 클라이언트에서 측정해요. `preparationMs`는 LLM 생성 전까지의 시간이에요. 모델 첫 로딩은 별도로 기록하고, 같은 DB·모델·질문으로 캐시 ON/OFF를 비교해요.

정상 표현뿐 아니라 발급 방법·개인 조회·복합 질문·새 번호 요청·번호이동을 하지 않는 조건도 확인해요. 승인 해제 후 Redis에 이전 키가 있어도 공식 답변으로 반환하면 안 돼요. 승인 해제·재승인으로 버전이 바뀌면 표현을 다시 등록해요.

## 7. 한계

등록된 질문 표현과 정규식 질문 범위는 모든 동의 표현·부정·문맥을 판별하지 못해요. 기존 실험에서 새 번호 요청·번호이동 부정·문맥 의존 질문을 통과시킨 사례가 있으며, 이 브랜치에서 해당 판정 정책을 수정하지 않았어요.

동적 임계값, 질문 정확 일치부터 단계별로 처리하는 계층적 캐시, 캐시 전용 운영 메트릭·비용 계산은 추가하지 않았어요. 일반 Actuator/Prometheus 지표와 판정 로그는 사용할 수 있어요. 운영 활성화 전에는 실제 사용 질문과 팀 검토로 허용 범위를 확인해야 해요.
