# 지원·검증 범위

이 문서는 현재 UBot-BE 저장소가 어떤 환경과 계약을 코드·테스트·배포 구성으로 확인하는지 설명합니다. 정식 SLA나 모든 환경에 대한 호환성 선언은 아닙니다.

## 실행 기준

| 영역 | 저장소의 기준 | 확인 근거 |
|---|---|---|
| JVM | Java 21 toolchain | `build.gradle`, GitHub Actions의 Temurin 21 빌드 |
| 애플리케이션 | Spring Boot 4.1.1, Spring AI 2.0.1 | `build.gradle` |
| 데이터베이스 | PostgreSQL 18, pgvector 0.8.6, PostGIS 3.6.4 | `infra/postgres/Dockerfile`, Testcontainers 통합 테스트 |
| 캐시·큐 신호 | Redis 8 | `docker-compose.yml` |
| 로컬 모델 | Ollama 0.34.0 또는 NVIDIA GPU가 있는 vLLM 구성 | `docker-compose.ollama.yml`, `docker-compose.vllm.yml` |
| 빌드 환경 | Ubuntu GitHub Actions와 Docker | `.github/workflows/ci.yml` |
| 로컬 실행 안내 | Windows PowerShell을 기본 예제로 사용하며 macOS/Linux는 Gradle·Compose 직접 명령 제공 | [Quickstart](quickstart.md) |

버전을 바꾸면 단순 기동뿐 아니라 Flyway, 임베딩 차원·Profile, 프롬프트와 모델 출력 형식, 배포 Compose 검증을 함께 다시 실행해야 합니다.

## 계약별 확인 범위

| 계약 | 현재 기준 | 보장하지 않는 것 |
|---|---|---|
| HTTP API | Controller·DTO·Security 설정과 실행 중 Swagger가 원본. 공통 응답은 `ApiResponse<T>` | 모든 엔드포인트의 장기 하위 호환성이나 별도 버전 API |
| 비동기 채팅 | 질문 접수는 `202 + jobId`, 결과는 작업 조회로 확인. DB가 작업·최종 결과의 원본 | 접수 응답이 답변 완료를 뜻한다는 가정, 정확히 한 번의 모델 실행 |
| 오류 | 애플리케이션 `code`와 HTTP 상태, 작업 실패의 `errorCode`를 함께 사용 | HTTP 상태만으로 모든 원인을 구분하는 방식 |
| 설정 | `.env.example`, `application.yml`, `@ConfigurationProperties`가 이름·기본값의 원본 | 실행 중 모든 설정의 동적 재적용 |
| DB | Flyway 마이그레이션을 순서대로 적용 | 이미 적용한 마이그레이션 파일의 수정·체크섬 변경, 자동 downgrade |
| AI 답변 | 지정 모델·프롬프트·검색 기준에서 근거와 출력 검사를 수행 | 모델 문장의 결정성, FAQ 밖 사실의 완전성, 모든 표현 공격 차단 |
| 외부 API | 카카오 API 성공 응답을 매장·경로 자료로 변환 | 외부 서비스 가용성·쿼터·데이터 정확도 보장 |

## 자동 테스트가 확인하는 것

- Testcontainers PostgreSQL·Redis에서 Flyway, 저장소, API와 비동기 작업 흐름을 검증합니다.
- LLM 관련 기본 테스트는 모의 모델과 로컬 HTTP 서버를 사용해 요청·오류·취소·복구 계약을 검증합니다.
- Worker·Redis·DB·모델 장애 경로는 관련 통합 테스트와 [장애 검증 기록](evidence/fault-recovery-20261023.md)에 근거를 남깁니다.
- 배포 CI는 Compose 해석, Prometheus 규칙, Nginx 설정, 배포 셸 문법과 이미지 안의 배포 파일 일치를 확인합니다.

기본 테스트 통과가 실제 GPU 처리량, 외부 네트워크, 운영 데이터 규모와 다중 인스턴스의 모든 조합을 증명하지는 않습니다.

## 별도 검증이 필요한 변경

- 모델·프롬프트·임베딩 모델·차원·Profile 버전 변경
- FAQ 검색 임계값, HNSW 설정, 캐시 판정 기준 변경
- Worker 동시 실행 수, DB pool, Redis와 모델 서버 용량 변경
- 공개 API 요청·응답, 오류 코드, 인증·권한 변경
- Flyway 스키마와 저장 데이터 의미 변경
- Nginx 제한, AWS 보안 그룹, 배포 순서와 롤백 조건 변경

이 변경에는 해당 단위·통합 테스트뿐 아니라 실제 호출 결과, 실패 경로와 기존 데이터 처리 방법을 함께 확인합니다.

## 현재 범위 밖

- 다중 리전 재해 복구와 자동 장애 조치
- 다중 AZ PostgreSQL·Redis 고가용성 보장
- GPU Auto Scaling Group의 상시 운영과 자동 용량 계획
- 통신사 과금·개통 원장과의 실시간 운영 연동
- 모델 서버에 전달된 요청의 원격 강제 취소 보장
- 외부 시스템 부작용의 exactly-once 보장

운영에 적용하기 전에는 [배포](deploy.md), [신뢰성 모델](reliability-model.md), [관측과 진단](operations/observability.md)의 전제와 제한을 함께 확인합니다.
