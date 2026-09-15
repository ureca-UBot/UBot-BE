# Contributing

팀의 Git / GitHub 협업 규칙입니다.

## 작업 흐름 한눈에 보기

```text
이슈 생성 → 작업 브랜치 생성 → 구현·커밋 → develop 최신화 → PR → 리뷰(1명 이상) → Merge
```

**이슈 1개 = 브랜치 1개 = PR 1개**가 기본입니다.

## 브랜치

```text
main
└── develop
    ├── feat/12-login-api
    ├── fix/15-duplicate-check
    └── refactor/18-service-layer
```

| 브랜치 | 용도 | 규칙 |
|---|---|---|
| `main` | 최종 제출용 | **직접 push 금지** |
| `develop` | 통합 브랜치 | PR로만 merge. CI 통과 필수 |
| 작업 브랜치 | 이슈 하나의 작업 | `develop`에서 분기 |

작업 브랜치 이름: `<type>/<이슈번호>-<영문 요약>` (예: `feat/12-login-api`)

## Type

이슈, 커밋, PR, 브랜치에 같은 type을 사용합니다.

| type | 의미 | 제목 표기 | 브랜치 표기 |
|---|---|---|---|
| `feat` | 새 기능 | `[Feat]` | `feat/` |
| `fix` | 버그 수정 | `[Fix]` | `fix/` |
| `refactor` | 기능 변화 없는 구조 개선 | `[Refactor]` | `refactor/` |
| `docs` | 문서 (README, API 명세 등) | `[Docs]` | `docs/` |
| `test` | 테스트 코드 | `[Test]` | `test/` |
| `chore` | 설정, 빌드, 라이브러리, Docker 등 | `[Chore]` | `chore/` |
| `style` | 포맷 정리 (로직 변경 없음) | `[Style]` | `style/` |

> 제목의 type은 **첫 글자 대문자**로 씁니다. PR 제목은 CI가 검사하므로 `[feat]`처럼 소문자로 쓰면 실패합니다.

## 1. 이슈 만들기

- 작업을 시작하기 **전에** 이슈를 먼저 만듭니다.
- GitHub 이슈 템플릿 중 작업에 맞는 것을 선택합니다.
- 제목: `[Type] 한글 작업 내용` (예: `[Feat] 로그인 기능 구현`)
- Assignees에 담당자를 지정합니다.
- 이슈 하나에는 작업 하나만 담습니다.

## 2. 작업 브랜치 만들기

항상 최신 `develop`에서 시작합니다.

```bash
git switch develop
git pull origin develop
git switch -c feat/12-login-api
```

## 3. 커밋

형식: `[Type] 한글 설명`

```bash
git commit -m "[Feat] JWT 로그인 구현"
```

## 4. PR 올리기

### 4-1. develop 최신 내용 반영

```bash
git fetch origin
git merge origin/develop
```

충돌이 나면 자신의 브랜치에서 해결합니다. 해결 방법은 [충돌 해결](#merge-conflict)을 참고하세요.

### 4-2. 로컬 검증

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

JDK 17 이상과 실행 중인 Docker가 필요합니다. Java 21은 없으면 Gradle이 자동으로 내려받습니다. 테스트는 Testcontainers로 전용 PostgreSQL 18 + pgvector 컨테이너를 생성하고 종료 시 정리합니다. 개발용 `.env`, 개발 Compose 서비스, Ollama는 필요하지 않으며 개발 DB·볼륨은 사용하지 않습니다. 첫 실행에는 테스트 이미지 다운로드가 필요할 수 있습니다.

애플리케이션을 직접 실행하는 `bootRun`은 별도입니다. 이때는 [quickstart](docs/quickstart.md)의 개발 환경을 준비하세요.

### 4-3. Push 후 PR 생성

```bash
git push origin feat/12-login-api
```

| 항목 | 규칙 |
|---|---|
| 대상 브랜치 | `develop` |
| 제목 | `[Type] 한글 설명` (예: `[Feat] 로그인 기능 구현`). 한글이 한 글자 이상 있어야 합니다 |
| 본문 | PR 템플릿의 항목(관련 이슈, 관련 도메인, 작업 내용, 체크리스트, 테스트 결과)을 채웁니다 |
| 이슈 연결 | 템플릿의 `Resolved: #12`에 이슈 번호를 적습니다. Merge되면 이슈가 자동으로 닫힙니다 |

제목 형식이 틀리면 `Validate PR Title` 체크가 실패합니다.

### PR은 작게

PR 하나에 여러 기능을 넣지 않습니다. 작은 PR이 리뷰와 오류 추적에 유리합니다.

```text
나쁜 예:  [Feat] 회원 시스템 전체 구현 (로그인, 회원가입, 탈퇴, 관리자, 쿠폰)

좋은 예:  [Feat] 로그인 기능 구현
          [Feat] 회원가입 기능 구현
          [Feat] 회원 탈퇴 기능 구현
```

## 5. 리뷰와 Merge

- 작성자가 바로 merge하지 않습니다. **다른 팀원 최소 1명이 확인한 뒤** merge합니다.
- 수정 요청을 받으면 **새 PR을 만들지 않고** 같은 브랜치에 커밋해서 push합니다. 기존 PR에 자동으로 반영됩니다.

```bash
git add .
git commit -m "[Fix] 쿠폰 중복 검증 로직 수정"
git push origin feat/12-login-api
```

## 협업 규칙

### 다른 사람 코드 수정

자신의 기능을 구현하다가 다른 팀원의 코드를 수정해야 하면 **먼저 공유**합니다.

### Merge Conflict

충돌이 났다고 다른 사람의 코드를 임의로 지우지 않습니다.

```text
<<<<<<< HEAD
내 코드
=======
팀원 코드
>>>>>>> develop
```

- 둘 중 어떤 코드가 필요한지 확인한 뒤 직접 정리합니다.
- 모르는 코드라면 작성한 팀원과 확인한 뒤 해결합니다.
- 해결한 뒤에는 테스트를 다시 실행합니다.

## 올리면 안 되는 것

### 비밀정보

`.env`, 비밀번호, JWT secret, API key, AWS key, private key는 커밋하지 않습니다.

설정 파일에는 실제 값 대신 환경변수를 씁니다.

```yaml
# 금지
password: myRealPassword123

# 사용
password: ${POSTGRES_PASSWORD}
```

- 실제 값은 `.env`에만 적습니다. `.env`는 이미 `.gitignore`에 등록되어 있습니다.
- 새 환경변수를 추가하면 `.env.example`과 [configuration reference](docs/reference/configuration.md)에 함께 추가합니다.

### 개인 설정

- **IDE 설정 파일:** `.idea/`, `.vscode/`, `.classpath`, `.project`, `.settings/` 등은 커밋하지 않습니다. 이미 `.gitignore`에 등록되어 있습니다. 팀에서 공유하기로 정한 설정은 예외입니다.
- **개인 PC 전용 설정:** `username: root`, `password: 1234` 같은 값을 application 설정에 넣어 push하지 않습니다.

## DB 마이그레이션 (Flyway)

테이블 생성·변경은 Flyway 마이그레이션 파일로만 관리합니다. DBeaver나 psql로 스키마를 직접 바꾸지 않습니다.

| 항목 | 규칙 |
|---|---|
| 위치 | `src/main/resources/db/migration/` |
| 파일명 | `V{순번}__{설명}.sql` (예: `V2__create_faq_tables.sql`) |
| 순번 | `develop`에 있는 마지막 번호 + 1 |
| 구분자 | `V{순번}`과 설명 사이는 밑줄 **두 개** (`__`). 하나면 Flyway가 파일을 인식하지 않습니다 |

- 이미 `develop`에 들어간 마이그레이션 파일은 **수정하지 않습니다.** 변경이 필요하면 새 번호의 파일을 추가합니다.
- PR을 올리기 전 `develop`을 반영했을 때 같은 번호가 이미 있으면, 내 파일의 번호를 다음 번호로 바꿉니다.
- 앱을 실행하면 마이그레이션이 자동으로 적용됩니다. 적용 이력은 `flyway_schema_history` 테이블에서 확인할 수 있습니다.

기존 로컬 DB에 테이블이 이미 있어도 동작하도록 `application.yml`에 `baseline-on-migrate: true`, `baseline-version: 0`이 설정되어 있습니다.

## CI

`develop`, `main` 대상 push와 PR에서 `Backend CI`가 실행됩니다.

```text
Checkout → Java 21 설정 → gradlew test (Testcontainers DB 생성·정리) → gradlew build -x test
```

- 로컬과 CI 모두 테스트 코드가 `test` 프로필과 Testcontainers의 DB 접속 정보를 적용합니다. CI에 별도 PostgreSQL 서비스를 띄우거나 개발 DB 비밀번호를 주입하지 않습니다.
- 이 테스트는 전용 DB 연결, vector extension, 1024차원/HNSW/COSINE 스키마와 벡터 저장·검색을 검증합니다. 임베딩은 테스트 전용 구현을 사용하므로 실제 Ollama/BGE-M3 경로의 통합 검증을 대신하지 않습니다.
