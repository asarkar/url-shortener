FROM azul/zulu-openjdk-alpine:11

COPY build/libs/*-all.jar /app.jar
CMD ["java", "-jar", "/app.jar"]
