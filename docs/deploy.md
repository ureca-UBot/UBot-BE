# 배포

> 문서 기준 시점: 2026-09-22 (`develop` = `1ac3bf2`)

> 현재 자동 배포(CD) 워크플로우는 없습니다. GitHub Actions에는 `Backend CI`와 `Validate PR Title`만 있습니다. 아래는 저장소에 들어 있는 배포용 파일을 손으로 사용하는 방법입니다.

## 구성 파일

| 파일 | 역할 |
|---|---|
| `Dockerfile` | 애플리케이션 이미지. `eclipse-temurin:21-jdk`에서 `bootJar`로 빌드하고 `21-jre`로 실행 |
| `docker-compose.deploy.yml` | `backend` 서비스 정의. 기본 `docker-compose.yml`의 `postgres`·`ollama`에 의존 |

## 이미지 빌드

```powershell
docker build -t ubot-be:local .
```

빌드는 컨테이너 안에서 Gradle을 실행하므로 로컬에 Java가 없어도 됩니다. 대신 매번 의존성을 새로 받습니다.

## 실행

배포용 Compose 파일은 단독으로 쓰지 않고 기본 파일과 함께 지정합니다. `backend`가 `postgres`, `ollama` 서비스에 의존하기 때문입니다.

```powershell
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d
```

- 이미지 이름은 `BACKEND_IMAGE` 환경변수로 바꿀 수 있습니다. 기본값은 `ubot-be:local`입니다.
- `backend`는 `.env`를 `env_file`로 읽고, 컨테이너 네트워크에 맞는 값(`POSTGRES_HOST=postgres`, `POSTGRES_PORT=5432`, `OLLAMA_BASE_URL=http://ollama:11434`)만 덮어씁니다.
- 애플리케이션은 호스트 `8080`에 노출됩니다.

확인:

```powershell
docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps
curl.exe http://localhost:8080/actuator/health
```

## 주의할 점

- **`docker-compose.deploy.yml`은 `SPRING_PROFILES_ACTIVE: local`을 사용합니다.** 운영 환경을 위한 별도 프로필은 아직 없습니다. 운영에 쓰기 전에 프로필과 설정값(로그, health 노출 범위, 비밀 값 주입 방식)을 다시 정해야 합니다.
- `.env`를 컨테이너에 그대로 주입하므로, 배포 서버에서는 개발용 `.env`를 복사해서 쓰지 말고 그 환경에 맞는 값을 따로 준비하세요.
- DB 데이터와 Ollama 모델은 Compose 볼륨에 저장됩니다. `docker compose down -v`는 둘 다 삭제합니다.

## CD 계획

CD 워크플로우 설계 초안은 GitHub 이슈로 관리합니다. 구현되면 이 문서에 실제 배포 흐름을 적습니다.
