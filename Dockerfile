FROM openjdk:8-jdk-alpine
RUN addgroup -S spring && adduser -S spring -G spring
ADD ./src/ src/
RUN chown -R spring:spring /src/
USER spring:spring
COPY amplify-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]