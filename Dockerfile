# syntax=docker/dockerfile:1
#
# 이미지 4종이 나온다. 빌드 컨텍스트는 항상 리포지토리 루트다.
#
#   --target runtime             api / scheduler / notifier  (JRE 슬림)
#   --target runtime-playwright  checker                     (브라우저 포함)
#   --target migration           Flyway 마이그레이션 Job      (JVM 없음)
#   --target frontend            정적 프론트 + nginx          (Node 런타임 없음)
#
# 앱 이미지는 MODULE build arg 로 어느 모듈의 jar 를 담을지 고른다.
# 코드가 같으므로 build 스테이지는 캐시를 공유한다.
#
# 산출물을 어디서 가져올지는 LAYERS_FROM / FRONTEND_FROM 으로 고른다.
#
#   build          (기본) 이 Dockerfile 안에서 Gradle/npm 을 돌린다 — 로컬 · compose · kind
#   prebuilt              러너가 미리 만든 dist/ 를 그대로 쓴다   — CD (라즈베리파이)
#
# 파이4(4GB)에서 Kotlin 6모듈을 컴파일하면 MySQL/Kafka/JVM 과 같은 메모리를 다툰다.
# jar 는 아키텍처 무관이므로 컴파일은 호스티드 러너가 하고, 파이는 COPY 만 한다.
# 스테이지를 파일로 나누지 않는 이유: 베이스 이미지와 JAVA_OPTS 가 두 벌이 되면 반드시 어긋난다.
# 쓰이지 않는 스테이지는 buildkit 이 그래프에서 잘라내므로 비용이 없다.

# Playwright Java 라이브러리 버전과 반드시 일치시킬 것 (build.gradle.kts 의 playwrightVersion).
ARG PLAYWRIGHT_VERSION=1.62.0
ARG LAYERS_FROM=build
ARG FRONTEND_FROM=frontend-build

########################################
# 1) Gradle 빌드 (모든 앱 모듈을 한 번에)
########################################
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 캐시 레이어: 빌드 스크립트만 먼저 복사한다.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY backend/contract/build.gradle.kts      backend/contract/
COPY backend/core/build.gradle.kts          backend/core/
COPY backend/app-api/build.gradle.kts       backend/app-api/
COPY backend/app-scheduler/build.gradle.kts backend/app-scheduler/
COPY backend/app-checker/build.gradle.kts   backend/app-checker/
COPY backend/app-notifier/build.gradle.kts  backend/app-notifier/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY backend ./backend
RUN ./gradlew --no-daemon bootJar -x test

# 각 모듈의 jar 를 layertools 로 분해해 둔다.
# bootJar 파일명을 전부 app.jar 로 통일해 뒀으므로 경로가 규칙적이다.
RUN for m in app-api app-scheduler app-checker app-notifier; do \
      mkdir -p /extracted/$m && cd /extracted/$m && \
      java -Djarmode=layertools -jar /workspace/backend/$m/build/libs/app.jar extract; \
    done

########################################
# 1-b) 미리 분해된 레이어 (CD 전용)
########################################
# 러너가 layertools 로 분해해 올린 결과물. 여기서는 파일을 옮기기만 한다.
#   dist/extracted/<module>/{dependencies,spring-boot-loader,snapshot-dependencies,application}
FROM scratch AS prebuilt
COPY dist/extracted/ /extracted/

# 아래 런타임 스테이지들은 이 별칭 하나만 본다.
FROM ${LAYERS_FROM} AS layers

########################################
# 2) 앱 런타임 — 슬림 (api / scheduler / notifier)
########################################
FROM eclipse-temurin:21-jre AS runtime
ARG MODULE=app-api
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

COPY --from=layers /extracted/${MODULE}/dependencies/ ./
COPY --from=layers /extracted/${MODULE}/spring-boot-loader/ ./
COPY --from=layers /extracted/${MODULE}/snapshot-dependencies/ ./
COPY --from=layers /extracted/${MODULE}/application/ ./

USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

########################################
# 3) 앱 런타임 — Playwright (checker)
########################################
# 이 베이스에는 JDK + Chromium/Firefox/WebKit + 시스템 의존성이 전부 들어있다.
# 그 대가로 이미지가 크다(~6GB). 우리는 Chromium 하나만 쓰므로 낭비가 있다.
#
# 줄이려면 temurin 베이스에서 다음처럼 Chromium 만 설치하면 된다:
#   java -cp app.jar com.microsoft.playwright.CLI install --with-deps chromium
# (`--with-deps` 가 리눅스 시스템 패키지까지 알아서 깔아준다)
# 다만 검증이 더 필요하므로, 우선은 공식 이미지로 확실하게 동작시킨다.
FROM mcr.microsoft.com/playwright/java:v${PLAYWRIGHT_VERSION}-jammy AS runtime-playwright
WORKDIR /app

# 베이스가 브라우저를 여기 미리 깔아둔다. 런타임 재다운로드를 막는다.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

COPY --from=layers /extracted/app-checker/dependencies/ ./
COPY --from=layers /extracted/app-checker/spring-boot-loader/ ./
COPY --from=layers /extracted/app-checker/snapshot-dependencies/ ./
COPY --from=layers /extracted/app-checker/application/ ./

RUN chown -R pwuser:pwuser /app
USER pwuser

EXPOSE 8080
# 컨테이너 메모리의 상당 부분을 Chromium 이 쓰므로 힙 비율을 낮게 잡는다.
# 슬림 이미지(75%)와 다른 값인 것이 의도된 것.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=45.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

########################################
# 4) 마이그레이션 Job
########################################
# JVM 앱을 띄우지 않고 Flyway CLI 만 실행한다 (이미지 ~800MB. alpine 이지만 JRE 를 품고 있다).
# 태그는 Spring Boot 가 관리하는 flyway-core 와 메이저를 맞춘다(11.x).
# 어긋나면 schema_history 테이블 포맷이 안 맞을 수 있다.
FROM flyway/flyway:11-alpine AS migration
# SQL 은 core 모듈이 소유한다. 여기서는 복사만 한다.
COPY backend/core/src/main/resources/db/migration /flyway/sql

########################################
# 5) 프론트엔드 — 정적 빌드 후 nginx
########################################
FROM node:22-alpine AS frontend-build
WORKDIR /fe
COPY frontend/package.json ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# CD 에서는 러너가 만든 dist/frontend 를 그대로 쓴다.
FROM scratch AS frontend-prebuilt
COPY dist/frontend/ /fe/dist/

FROM ${FRONTEND_FROM} AS fe

FROM nginx:1.27-alpine AS frontend
# Node 는 위 스테이지에서만 쓰였다. 최종 이미지에 Node 런타임은 없다.
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=fe /fe/dist /usr/share/nginx/html
EXPOSE 8080
