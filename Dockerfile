# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
# Cache dependencies in their own layer
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline || true
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp "$(ls target/*.jar | grep -v -e '\.original$' -e '-plain\.jar$' | head -1)" /build/app.jar

FROM eclipse-temurin:17.0.21_9-jre-alpine
# Pick up OS package fixes published after the base image was built
RUN apk upgrade --no-cache \
    && addgroup -S app \
    && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=docker \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Xss512k"
EXPOSE 8080 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
