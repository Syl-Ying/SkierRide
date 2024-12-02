# Build Stage
FROM maven:3.8.7-amazoncorretto-17 AS build
WORKDIR /home/app
COPY libs/swagger-java-client-1.0.0.jar ./libs/swagger-java-client-1.0.0.jar
RUN mvn install:install-file -Dfile=/home/app/libs/swagger-java-client-1.0.0.jar -DgroupId=io.swagger -DartifactId=swagger-java-client -Dversion=1.0.0 -Dpackaging=jar

COPY pom.xml .
COPY src ./src
RUN mvn clean package

# Package Stage
FROM openjdk:17-jdk-slim
WORKDIR /usr/local/lib
COPY --from=build /home/app/target/Consumer-1.0-SNAPSHOT-jar-with-dependencies.jar /usr/local/lib/consumer.jar
ENTRYPOINT ["java", "-jar", "/usr/local/lib/consumer.jar"]