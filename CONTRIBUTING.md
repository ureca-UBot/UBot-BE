# Contributing

팀의 Git / GitHub 협업 규칙입니다. 코딩 컨벤션(팀 Notion)과 Notion 규칙이 현재 코드·CI와 다른 점은 맨 아래 [팀 Notion 규칙](#팀-notion-규칙)의 접힌 부분에 있습니다.

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

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

JDK 17 이상과 실행 중인 Docker가 필요합니다. Java 21은 없으면 Gradle이 자동으로 내려받습니다. 테스트는 Testcontainers로 전용 PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4 컨테이너를 생성하고 종료 시 정리합니다. 개발용 `.env`는 필요하지 않으며 개발 DB·볼륨은 사용하지 않습니다. 첫 실행에는 DB 이미지 빌드와 패키지 다운로드가 필요할 수 있습니다. 실제 Ollama가 필요한 분석 테스트 하나는 따로 켤 때만 실행됩니다([troubleshooting](docs/troubleshooting.md#intentclassificationanalysis가-실행되지-않음)).

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

DB extension과 테이블·인덱스의 생성·변경은 Flyway 마이그레이션 파일로만 관리합니다. DBeaver나 psql로 스키마를 직접 바꾸지 않습니다.

| 항목 | 규칙 |
|---|---|
| 위치 | `src/main/resources/db/migration/` |
| 파일명 | `V{순번}__{설명}.sql` (예: `V2__create_faq_tables.sql`) |
| 순번 | `develop`에 있는 마지막 번호 + 1 (문서 기준 시점의 마지막 번호는 `V15`) |
| 구분자 | `V{순번}`과 설명 사이는 밑줄 **두 개** (`__`). 하나면 Flyway가 파일을 인식하지 않습니다 |

- 이미 `develop`에 들어간 마이그레이션 파일은 **수정하지 않습니다.** 변경이 필요하면 새 번호의 파일을 추가합니다.
- PR을 올리기 전 `develop`을 반영했을 때 같은 번호가 이미 있으면, 내 파일의 번호를 다음 번호로 바꿉니다.
- 앱을 실행하면 마이그레이션이 자동으로 적용됩니다. 적용 이력은 `flyway_schema_history` 테이블에서 확인할 수 있습니다.

기존 로컬 DB에 테이블이 이미 있어도 동작하도록 `application.yml`에 `baseline-on-migrate: true`, `baseline-version: 0`이 설정되어 있습니다.

## GitHub Actions

| 워크플로우 | 파일 | 실행 시점 | 내용 |
|---|---|---|---|
| `Backend CI` | `ci.yml` | `develop`, `main` 대상 push와 PR | 테스트와 빌드 |
| `Validate PR Title` | `commit-message.yml` | PR 생성·수정·동기화 | PR 제목이 `[Type] 한글 설명` 형식인지 검사 |
| `Backend Manual CD` | `cd-manual.yml` | Actions 화면에서 수동 실행 (`develop`만) | EC2 스테이징 배포. [deploy.md](docs/deploy.md#수동-cd-backend-manual-cd) 참고 |

### Backend CI

```text
Checkout → Java 21 설정 → gradlew test (Testcontainers DB 생성·정리) → gradlew build -x test
```

- 로컬과 CI 모두 테스트 코드가 `test` 프로필과 Testcontainers의 DB 접속 정보를 적용합니다. CI에 별도 PostgreSQL 서비스를 띄우거나 개발 DB 비밀번호를 주입하지 않습니다.
- `UbotBeApplicationTests`는 전용 DB 연결, Flyway로 만든 vector·postgis extension 버전, 1024차원/HNSW/COSINE 스키마와 벡터 저장·검색을 검증합니다. 나머지 도메인 테스트(인증·채팅·FAQ·금지어·매장·길찾기 등)도 함께 실행됩니다.
- 임베딩과 LLM은 테스트 전용 구현·모의 객체를 사용하므로 실제 Ollama/BGE-M3 경로의 통합 검증을 대신하지 않습니다.
- 실제 Ollama를 호출하는 `IntentClassificationAnalysis`는 환경변수 `RUN_INTENT_ANALYSIS=true`가 있을 때만 실행되므로 CI에서는 건너뜁니다.

## 팀 Notion 규칙

<details>
<summary>코딩 컨벤션(Notion 원문)과 Notion 규칙이 현재 코드·CI와 다른 점 (펼치기)</summary>
<br>

팀 Notion "Github 규칙 / 코딩 컨벤션" 페이지 내용입니다. Git 규칙은 위 본문(CI·PR 템플릿 기준)을 따릅니다. 비교 기준: 문서 맨 위의 UBot-BE 기준 커밋, Notion 페이지는 2026-09-28 내보내기본.

### 코딩 컨벤션 (Notion 원문)

#### 1. 네이밍 규칙

이름만 보고도 클래스인지, 변수인지, 스프링 빈 객체인지 알 수 있어야 합니다.

| 대상 | 규칙 | 예 |
|---|---|---|
| 클래스 & 인터페이스 | 대문자 파스칼케이스(PascalCase) | `UserService`, `OrderController` |
| 메서드 & 변수 | 소문자 카멜케이스(camelCase) | `getUserById()`, `totalAmount` |
| 상수 (`static final`) | 대문자와 언더바(UPPER_SNAKE_CASE) | `MAX_LOGIN_ATTEMPTS` |
| 패키지명 | 소문자만 사용 (가급적 단수형) | `com.example.project.user.controller` |

- 중괄호는 한 칸 엔터를 칩니다.

```java
public abc(){
}
```

- 탭은 공백 4칸 크기의 탭을 사용합니다.

#### 2. 패키지 구조

도메인 단위로 패키지를 구성합니다.

```text
faq/
├── controller/
├── service/
├── repository/
├── entity/
├── dto/
│   ├── request/
│   └── response/
└── ...
```

공통 기능은 `global` 패키지에서 관리합니다.

```text
global/
├── config/
├── exception/
├── security/
└── ...
```

#### 3. Entity

- Entity 클래스는 이름만 작성합니다. (`Faq`, `User`, `FaqLog` ...)
- `@Column` 안에는 `name`만 작성하고, 기타 속성은 작성하지 않습니다. (DB 직접 관리)

```java
@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Table(name = "faq")
public class Faq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question")
    private String question;

    @Column(name = "answer")
    private String answer;
}
```

#### 4. DTO 작성 규칙

- `record`를 사용하고, 이름은 `~ResponseDto`, `~RequestDto`로 작성합니다.

```java
public record TeamResponseDto(
        Long id,
        String name,
        String ownerNickname,
        int currentMember
) {

    public static TeamResponseDto from(Team team) {
        return new TeamResponseDto(
                team.getId(),
                team.getName(),
                team.getOwner().getNickname(),
                team.getMembers().size()
        );
    }
}
```

#### 5. Service 네이밍

- Service 클래스는 도메인명 뒤에 `Service`를 붙입니다. (`FaqService`, `UserService`, `StoreService`)
- 서비스의 역할이 명확하게 분리되는 경우 역할을 이름에 포함합니다.

| 서비스 | 역할 |
|---|---|
| `FaqCRUDService` | FAQ 조회 / 생성 / 수정 / 삭제 |
| `FaqVectorService` | FAQ 벡터 저장 / 유사도 검색 / Top-K 검색 |
| `EmbeddingService` | 문자열을 임베딩 벡터로 변환 |
| `ChatService` | 채팅 |

- 하나의 Service에 서로 다른 책임을 과도하게 넣지 않습니다.

#### 6. Service 메서드 네이밍

메서드는 **동사 + 대상** 형태를 기본으로 합니다.

| 동작 | 예 |
|---|---|
| 생성 | `createFaq()`, `createUser()` |
| 단일 조회 | `getFaq()`, `getUser()` |
| 목록 조회 | `getFaqList()`, `getUserList()` |
| 수정 | `updateFaq()`, `updateUser()` |
| 삭제 | `deleteFaq()`, `deleteUser()` |
| 검증 | `validateFaq()`, `validateUser()` |
| 존재 여부 | `existsFaq()`, `existsUser()` |

Repository의 조회 의미를 강조해야 하는 경우 `find` 사용도 가능하지만, Service 계층에서는 팀 내에서 `get` 또는 `find` 중 하나로 통일합니다.

#### 7. Repository 네이밍

- Repository는 Entity 이름에 `Repository`를 붙입니다.

```text
FaqEntity   → FaqRepository
UserEntity  → UserRepository
StoreEntity → StoreRepository
```

```java
public interface FaqRepository
        extends JpaRepository<FaqEntity, Long> {
}
```

- Repository 메서드는 Spring Data JPA 네이밍 규칙을 따릅니다. (`findById()`, `findByQuestion()`, `findByStoreId()`, `existsByQuestion()`, `deleteById()`)

#### 8. Controller 네이밍

- Controller는 도메인명 뒤에 `Controller`를 붙입니다. (`UserController`, `AdminController`, `ChatController`)
- 의논사항: Admin과 User 모두 FAQ 조회를 할 수 있을 때, 공통 부분을 `FaqController`로 뺄 것인가?
  - 제안된 의견: `AdminController`, `UserController`에 각각 FAQ 조회 경로를 넣는다. 중복은 생기지만 각 역할이 어떤 행동을 할 수 있는지 알기 쉽다.
- Controller의 메서드는 HTTP 요청이 어떤 동작을 하는지 알 수 있도록 작성합니다. (`createFaq()`, `getFaq()`, `getFaqList()`, `updateFaq()`, `deleteFaq()`)

### Notion 규칙과 다른 점

#### Git / GitHub 규칙

이 문서의 Git 규칙 본문은 CI(`Validate PR Title`)와 PR 템플릿에 맞춰 적혀 있습니다. Notion과 다른 점은 아래와 같습니다.

| 항목 | Notion | 실제 (CI·PR 템플릿) |
|---|---|---|
| 제목 type 표기 | `[feat]` 소문자 | `[Feat]`처럼 첫 글자 대문자. 소문자면 CI가 실패 |
| 이슈 연결 | `Closes #번호` | `Resolved: #번호` |
| 이슈 제목 예시 | `[Auth] 회원가입 api 구현`, `[feat] ...`가 섞여 있음 | `[Type] 한글 설명` |
| 브랜치 이름 | `[feat]/{이슈번호}`, `feat/login` 등이 섞여 있음 | `<type>/<이슈번호>-<영문 요약>` (예: `feat/12-login-api`) |
| 커밋 예시 | `[fix]: ...` (콜론) | `[Fix] ...` |
| PR 본문 | 작업 내용 / 변경 사항 / 테스트 / 관련 Issue | 관련 이슈 / 관련 도메인 / 작업 내용 / 체크리스트 / 테스트 시각 정보 / 리뷰 요구사항 |

#### 코딩 컨벤션: 현재 코드가 다른 부분

| 절 | Notion | 현재 코드 |
|---|---|---|
| 1. 들여쓰기 | 4칸 크기의 탭 | 탭 82개 파일, 공백 53개 파일로 섞여 있음 (`ai`, `prompt`, `llm`, `embedding`, `store` 등은 공백) |
| 2. 공통 패키지 | `global/` 아래 `config/`, `exception/`, `security/` | `common/`에 `ApiResponse`, `ErrorCode`, `GlobalException`, `GlobalExceptionHandler`, `PageResponseDto`, `OpenApiConfig`, `exception/CommonErrorCode`. `global/`에는 `config/AsyncConfig` 하나뿐. 보안 설정은 `auth/config/` |
| 2. 도메인 하위 패키지 | `controller`, `service`, `repository`, `entity`, `dto` | 추가로 `enums/`, `exception/`(`<도메인>ErrorCode`, `<도메인>Exception`), `client/`(외부 API: `direction`, `location`, `llm`) |
| 2. DTO 폴더 | `dto/request`, `dto/response` | `store`, `direction`, `location`은 `dto/` 바로 아래에 둠 |
| 3. `@Table` | 예시에 `@Table(name = ...)` | `Faq`, `FaqCategory`, `FaqLog`, `OldFaq`는 `@Table` 없이 클래스 이름으로 매핑 |
| 4. DTO 이름 | `~RequestDto`, `~ResponseDto` | `location/dto/LocationSearchResponse`는 `Dto`로 끝나지 않음 |
| 5. 기본 CRUD 서비스 | `FaqCRUDService` | `FaqService`처럼 `<도메인>Service`. 역할을 분리한 서비스는 `FaqVectorService`, `EmbeddingService`, `ChatAttemptsService`, `ForbiddenWordFilterService`, `AdminStoreService` |
| 6. 조회 접두사 | `get` 또는 `find` 중 하나로 통일 | `get`으로 통일 (#43 리뷰). 서비스의 공개 `find*` 메서드는 0개 |
| 7. Repository 예시 | `FaqEntity → FaqRepository` | 엔티티에 `Entity` 접미사가 없으므로(3절) `Faq → FaqRepository` |
| 7. 저장소 분리 | 언급 없음 | JdbcTemplate 저장소는 역할을 이름에 넣음 (`FaqVectorRepository`). 매장은 JPA 저장소(`StoreJpaRepository`, `ServiceTypeJpaRepository`)와 SQL 파일 기반 저장소(`StoreRepository`)를 나눠 씀 |
| 8. 관리자 컨트롤러 | 의논사항: `AdminController`·`UserController`에 각각 넣는 안 | 도메인별 `Admin<도메인>Controller`로 나눔 (`AdminFaqController`, `AdminStoreController`, `AdminForbiddenWordController`), 경로는 `/admin/...` |

#### Notion에 없는 규칙 (리뷰 합의·현재 코드 관행)

- **예외**: 커스텀 예외는 `GlobalException`을 상속하고, 도메인별 `<도메인>ErrorCode` enum을 `PREFIX-번호` 코드로 둡니다(#36, #43, #73). 작성법은 [`exception.md`](src/main/java/com/ubot/common/manual/exception.md)를 따릅니다.
- **컨트롤러 반환형**: `ApiResponse<T>`를 반환합니다(#82 리뷰). `201 Created`, `204 No Content`처럼 상태 코드를 바꿔야 할 때만 `ResponseEntity`로 감쌉니다(`AdminStoreController`). 작성법은 [`api-response.md`](src/main/java/com/ubot/common/manual/api-response.md)를 따릅니다.
- **오류 응답**: 컨트롤러에서 `try-catch`로 직접 응답하지 않고 예외를 던져 `GlobalExceptionHandler`가 변환하게 합니다.
- **로그인 사용자**: 요청 본문이 아니라 `@AuthenticationPrincipal CustomUserDetails`에서 가져옵니다(#82 리뷰).
- **검증 책임**: 요청이 유효한지(질문 형식, 재시도 키 등)는 요청을 받는 서비스가 검증하고, 저장을 맡은 서비스에 넣지 않습니다(#82 리뷰).
- **요청 DTO 검증**: Bean Validation 어노테이션(`@NotBlank`, `@Size` 등)과 컨트롤러의 `@Valid`를 사용합니다.
- **enum 저장**: enum 필드는 `@Enumerated(EnumType.STRING)`으로 저장합니다.
- **설정값**: timeout, Top-K, 유사도 기준, 재시도 횟수처럼 환경에 따라 바꿀 수 있는 값은 코드 상수로 두지 않고 환경변수로 뺍니다(#36, #82 리뷰). 새 환경변수는 `.env.example`과 [configuration reference](docs/reference/configuration.md)에 함께 추가합니다.

</details>
