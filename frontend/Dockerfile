# The client: built with Maven in a first image, then run on a Java runtime
# with the libraries Swing needs to open its window on the X server of the
# host (DISPLAY and the X socket come from docker-compose.yml).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /client
# The libraries first: they stay in the cache while only the code changes.
COPY pom.xml .
RUN mvn -q dependency:copy-dependencies -DoutputDirectory=lib
COPY src src
RUN mvn -q package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends \
        libxext6 libxrender1 libxtst6 libxi6 fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /client
COPY --from=build /client/lib lib
COPY --from=build /client/target/connect4-client-1.0-SNAPSHOT.jar client.jar
# "server" is the name of the server in docker-compose.yml.
CMD ["java", "-Dserver.host=server", "-cp", "client.jar:lib/*", "com.lso.MainController"]
