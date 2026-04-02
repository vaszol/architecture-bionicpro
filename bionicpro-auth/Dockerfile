# Этап сборки
FROM maven:3.9.9-amazoncorretto-21-alpine AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

FROM amazoncorretto:21-alpine

WORKDIR /app

# Создаем пользователя
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser

# Копируем jar из сборщика
COPY --from=builder /app/target/bionicpro-auth-1.0.0.jar app.jar

USER appuser

EXPOSE 8001

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]