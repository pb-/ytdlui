FROM clojure:tools-deps-1.10.3.1058 AS builder

RUN mkdir -p /build
WORKDIR /build

# cache deps
COPY deps.edn /build
RUN clojure -P
RUN clojure -P -T:build

COPY build.clj /build
COPY src src/
COPY resources resources/

ARG version=UNKNOWN
RUN echo "${version}" | cut -c -7 > resources/version

RUN clojure -T:build uber

# ======================================================================================
FROM debian:11.2-slim

RUN apt-get update && apt-get install -y python3 python3-venv openjdk-17-jre-headless ffmpeg

WORKDIR /app
VOLUME /app/data
EXPOSE 8080/tcp

ENV STORAGE_PATH=/app/data
ENV LC_ALL=C.UTF-8
ENV LANG=C.UTF-8

COPY --from=builder /build/target/ytdlui.jar /app/main.jar

ENTRYPOINT ["java", "-Dfile.encoding=UTF8", "-cp", "main.jar", "clojure.main", "-m", "ytdlui.core"]
