# --- Этап сборки: собираем jar через Maven ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Сначала только pom.xml, чтобы Docker закэшировал слой с зависимостями —
# при повторных сборках без изменений в pom.xml зависимости не перекачиваются заново.
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# --- Этап запуска: лёгкий образ только с JRE и готовым jar ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Render сам подставляет порт через переменную окружения PORT — Spring Boot
# должен слушать именно её (см. правку server.port в application.properties).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]