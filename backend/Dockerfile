FROM eclipse-temurin:21-jre

WORKDIR /opt/chat-app

COPY backend/target/universal/stage/ ./

EXPOSE 8080

ENTRYPOINT ["bin/backend"]
