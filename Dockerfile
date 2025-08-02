FROM maven:3.9.7-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM bellsoft/liberica-openjdk-alpine:17
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
ENV DEFAULT_PROCESSOR_URL=http://payment-processor-default:8080
ENV REDIS_URI=redis://rinha-redis:6379
CMD ["java", "-jar", "app.jar"]