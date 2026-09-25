# The server: compiled with gcc in a first image, then run from a small one
# that holds only the binary.
FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev make \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY Makefile .
COPY include include
COPY src src
RUN make

FROM debian:bookworm-slim
COPY --from=build /src/bin/server /usr/local/bin/server
EXPOSE 8080
# stdbuf: one line at a time to the logs (docker compose logs), not in blocks.
CMD ["stdbuf", "-oL", "server"]
