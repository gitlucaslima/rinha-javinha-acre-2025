FROM maven:3.9.7-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM bellsoft/liberica-openjdk-alpine:17
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

# Environment variables
ENV DEFAULT_PROCESSOR_URL=http://payment-processor-default:8080
ENV REDIS_URI=redis://rinha-redis:6379

# JVM otimizações para containers com pouca memória
ENV JAVA_OPTS="-server \
    -Xms64m \
    -Xmx128m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=100 \
    -XX:+UseStringDeduplication \
    -XX:+UseCompressedOops \
    -XX:+UseCompressedClassPointers \
    -XX:+TieredCompilation \
    -XX:TieredStopAtLevel=1 \
    -XX:+DisableExplicitGC \
    -XX:MaxMetaspaceSize=64m \
    -XX:CompressedClassSpaceSize=32m \
    -XX:ReservedCodeCacheSize=32m \
    -Djava.awt.headless=true \
    -Djava.net.preferIPv4Stack=true \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=UTC"

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]