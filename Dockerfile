FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

COPY src ./src

RUN ./gradlew bootJar --no-daemon \
    && cp "$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -print -quit)" /app/app.jar


FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/app.jar app.jar
# 빌드 단계의 Gradle이 받은 Kiwi 형태소 분석기 모델입니다. 모델이 없으면 백엔드가 기동하지 않습니다.
COPY --from=builder /app/.kiwi/model kiwi-model
ENV KIWI_MODEL_PATH=/app/kiwi-model

# Keep the deployment script and Compose definition at the same commit as the image.
# SSM extracts this directory before replacing the existing BE containers.
COPY docker-compose.api.yml docker-compose.worker.yml /opt/ubot-deploy/
COPY infra/nginx/nginx.api.conf.template /opt/ubot-deploy/infra/nginx/
COPY tools/deploy/aws-be-deploy.sh /opt/ubot-deploy/tools/deploy/

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
