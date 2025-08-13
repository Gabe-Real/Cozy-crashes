FROM eclipse-temurin:24-jdk-alpine

COPY build/libs/cozy-crashes-*-all.jar /usr/local/lib/cozy-crashes.jar

RUN mkdir /bot
RUN mkdir /bot/data
RUN mkdir /bot/plugins

WORKDIR /bot

VOLUME /bot/data
VOLUME /bot/plugins

ENTRYPOINT ["java", "-Xms2G", "-Xmx2G", "-jar", "/usr/local/lib/cozy-crashes.jar"]
