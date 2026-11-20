# ---------- Giai đoạn 1: build (JDK + Maven wrapper) ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY api api
COPY src src
RUN ./mvnw -q -B -DskipTests package

# ---------- Giai đoạn 2: chạy (chỉ JRE) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 app && mkdir -p /app/.local-data/uploads && chown -R app /app
USER app

COPY --from=build /src/target/gowithbae-be-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
