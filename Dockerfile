FROM eclipse-temurin:22-jdk-jammy AS build
WORKDIR /src
COPY . .
RUN ./gradlew :gateway-app:bootJar --no-daemon

FROM eclipse-temurin:22-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system gateway \
    && useradd --system --gid gateway --home-dir /app --shell /usr/sbin/nologin gateway
WORKDIR /app
COPY --from=build /src/gateway-app/build/libs/jvm-llm-gateway.jar /app/jvm-llm-gateway.jar
USER gateway
EXPOSE 8080 8081
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/jvm-llm-gateway.jar"]
