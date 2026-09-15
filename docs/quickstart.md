# 로컬에서 UBot-BE 실행하기

## 완료 기준

이 문서를 마치면 `http://localhost:8080/actuator/health`에서 애플리케이션과 DB가 모두 `UP`으로 표시됩니다.

## 요구사항

| 프로그램 | 확인 명령 |
|---|---|
| JDK 17 이상 (Gradle 실행용) | `java -version` |
| Docker Desktop (실행 중이어야 함) | `docker compose version` |
| Git | `git --version` |

Ollama는 Compose 컨테이너로 실행합니다. 호스트에 별도로 설치할 필요가 없습니다.

프로젝트는 Java 21로 빌드합니다. PC에 Java 21이 없으면 첫 `gradlew` 실행 때 Gradle이 자동으로 내려받습니다. 인터넷 연결이 필요하며, 받은 JDK는 사용자 폴더의 `.gradle/jdks`에 저장됩니다. Eclipse에서 직접 실행할 때는 Eclipse에 등록된 JDK를 쓰므로 Java 21 JDK가 등록되어 있어야 합니다.

`.env.example` 기준 호스트 포트 `15432`(PostgreSQL), `8080`(Spring Boot), `11435`(Ollama)가 비어 있어야 합니다. 컨테이너 내부 포트 `5432`, `11434`와 구분하세요.

> 명령은 Windows PowerShell 기준입니다. macOS/Linux에서는 `Copy-Item` 대신 `cp`, `.\gradlew.bat` 대신 `./gradlew`를 사용하세요.

## 1. 저장소 받기

```powershell
git clone https://github.com/ureca-final-project-temp/UBot-BE.git
cd UBot-BE
git switch develop
git pull origin develop
```

개발은 `develop` 브랜치를 기준으로 합니다. 브랜치 규칙은 [CONTRIBUTING.md](../CONTRIBUTING.md)를 참고하세요.

이후 모든 명령은 **프로젝트 루트**(`UBot-BE/`)에서 실행합니다.

## 2. `.env` 만들기

처음 실행할 때만 복사합니다. 기존 `.env`가 있으면 덮어쓰지 말고 필요한 키만 비교하세요.

```powershell
Copy-Item .env.example .env
```

`.env`를 열어 `POSTGRES_PASSWORD`를 충분히 긴 임의의 영문·숫자 값으로 바꿉니다. 아래는 형식 예시이며 그대로 사용하지 마세요. 따옴표로 감싸지 않습니다.

```dotenv
POSTGRES_PASSWORD=ReplaceWithYourOwnLongRandomASCIIValue
```

> 현재 `.env`를 Docker Compose와 Spring이 서로 다른 문법으로 읽습니다. 한글·따옴표·특수문자는 값이 달라질 수 있으며, 영문·숫자로 제한하는 것은 근본 해결이 아닌 임시 회피책입니다. [현재 파싱 제약](reference/configuration.md#env-파싱-제약-아직-미해결)을 확인하세요.

> **다음 단계로 넘어가기 전에 비밀번호를 정하세요.** 이 값은 DB 데이터 디렉터리가 **처음 초기화될 때만** 적용됩니다. 나중에 바꾸는 방법은 [troubleshooting](troubleshooting.md#password-authentication-failed)을 참고하세요.

`.env`는 Git에 올라가지 않습니다(`.gitignore` 등록됨). 비밀번호는 이 파일에만 적습니다.

## 3. PostgreSQL + pgvector + PostGIS와 Ollama 실행

```powershell
docker compose up -d --build
docker compose ps -a
```

`postgres`와 `ollama` 서비스의 STATUS가 `(healthy)`가 될 때까지 기다립니다. 실제 컨테이너 이름은 Compose 프로젝트명에 따라 달라집니다.

DB 이미지에는 pgvector와 PostGIS 실행 파일이 설치되어 있습니다. DB별 extension 활성화는 5단계에서 애플리케이션을 시작할 때 Flyway V1·V2가 담당하며, 신규 DB와 기존 볼륨에 같은 순서로 적용됩니다. 문제가 생기면 [Flyway 확인 절차](troubleshooting.md#vector-또는-postgis-extension이-없음)를 따르세요.

## 4. Embedding 모델 준비 확인

`ollama-init` 서비스가 `.env`의 `OLLAMA_EMBEDDING_MODEL`(기본 `bge-m3:567m`)을 같은 Compose의 Ollama에 다운로드합니다. 첫 다운로드에는 시간이 걸릴 수 있습니다.

```powershell
docker compose logs --tail=30 ollama-init
docker compose ps -a ollama-init
docker compose exec ollama ollama list
```

`ollama-init`의 종료 코드가 0이고 모델 목록에 `bge-m3:567m`이 보이면 다음 단계로 진행합니다. 모델을 변경했다면 해당 이름으로 확인하세요.

호스트에서 `ollama pull`을 실행하면 기본적으로 별도 호스트 Ollama에 다운로드할 수 있으므로 여기서는 사용하지 않습니다. 다운로드 실패 시 [모델 준비 문제](troubleshooting.md#ollama-모델이-준비되지-않음)를 참고하세요.

## 5. Spring Boot 실행

```powershell
.\gradlew.bat bootRun
```

Eclipse에서 `UbotBeApplication`을 실행해도 됩니다. 이 경우 Run Configuration의 작업 디렉터리가 프로젝트 루트여야 `.env`를 읽을 수 있습니다(기본값이 프로젝트 루트입니다).

별도 설정이 없으면 `local` 프로필로 실행됩니다.

## 6. 동작 확인

새 터미널에서:

```powershell
curl.exe http://localhost:8080/actuator/health
```

기대 결과(일부):

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", ... },
    ...
  }
}
```

- `status`가 `UP` → 애플리케이션 기동 성공
- `components.db.status`가 `UP` → DB 연결 성공

둘 다 `UP`이면 애플리케이션과 DB의 기본 기동 확인이 끝났습니다. Health 응답만으로 실제 임베딩 생성·벡터 검색·RAG 응답까지 검증된 것은 아닙니다.

## 테스트만 실행하려면

JDK 17 이상과 실행 중인 Docker만 준비한 뒤 실행합니다.

```powershell
.\gradlew.bat test
```

Testcontainers가 테스트 전용 PostgreSQL + pgvector + PostGIS 컨테이너를 생성하고 Flyway V1·V2 및 두 extension을 검증한 뒤 종료 시 정리합니다. 위의 `.env` 작성·Compose 기동·Ollama 모델 다운로드 단계는 테스트에 필요하지 않습니다. 개발 DB의 볼륨을 공유하지 않습니다.

## 실패했다면

증상별 해결 방법은 [troubleshooting.md](troubleshooting.md)에 있습니다.

## 다음 단계

- DB를 직접 조회하려면 → [how-to/db-access.md](how-to/db-access.md)
- 설정값의 의미가 궁금하면 → [reference/configuration.md](reference/configuration.md)
- 코드를 수정하고 PR을 올리려면 → [../CONTRIBUTING.md](../CONTRIBUTING.md)

## 정리하기

```powershell
docker compose down      # 개발 컨테이너 제거, DB·모델 볼륨 유지
```

> `docker compose down -v`는 PostgreSQL 데이터와 Ollama 다운로드 모델이 담긴 볼륨을 모두 삭제합니다. 테스트 DB 정리에 필요한 명령이 아닙니다. 개발 데이터를 초기화하려는 경우에만 백업과 삭제 범위를 확인한 뒤 사용하세요.
