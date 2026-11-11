# syntax=docker/dockerfile:1
# 코리거 API 이미지. GitHub Actions(deploy.yml)가 만들어 GHCR에 올린다.
# 1) Gradle로 bootJar를 만들고 2) 레이어별로 풀어(의존성 / 앱 코드) 3) JRE 이미지에 복사한다.
# 코드만 바뀌면 서버는 작은 앱 레이어만 새로 받는다.
# 배포 워크플로는 1)·2)를 러너에서 Gradle 캐시로 미리 하고, 그 결과(extracted/)를 app 단계 대신 넣는다
# (build-contexts: app=build/image). 이미지 안의 Gradle 캐시는 러너가 매번 새것이라 비어 있어서, 여기서 만들면
# 배포마다 Gradle과 라이브러리를 새로 받는다. 로컬에서 그냥 docker build 하면 app 단계가 처음부터 만든다.

FROM eclipse-temurin:21-jdk AS app
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 의존성만 먼저 받아 두면 코드만 바뀐 빌드에서 이 단계를 캐시로 건너뛴다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
# 테스트는 PR CI에서 이미 돈다. 여기서는 실행할 jar만 만든다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -n 1)" application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination /extracted

FROM eclipse-temurin:21-jre
# 헬스체크용 curl. 나머지는 JRE 이미지 그대로 쓴다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=app /extracted/dependencies/ ./
COPY --from=app /extracted/spring-boot-loader/ ./
COPY --from=app /extracted/snapshot-dependencies/ ./
COPY --from=app /extracted/application/ ./
USER app
# 컨테이너 메모리 상한의 75%까지 힙으로 쓰고, 메모리가 모자라면 멈춰 다시 띄워지게 한다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    KORLEAGUER_DATA_SOURCE=mlb
EXPOSE 8080
# 뜨는 중(시작 유예 90초)에는 2초마다 확인해 뜨자마자 healthy가 되게 한다(Docker 25+). 그 뒤로는 10초마다.
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --start-interval=2s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "application.jar"]
