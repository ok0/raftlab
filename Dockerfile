# raftlab container image (charter R27). Build: docker build --build-arg RAFTLAB_VERSION=1.2.3 -t raftlab:1.2.3 .
FROM eclipse-temurin:24-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS build
WORKDIR /src
COPY . .
ARG RAFTLAB_VERSION=0.0.0-dev
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :raftlab-cli:installDist "-Praftlab.version=${RAFTLAB_VERSION}"

FROM eclipse-temurin:24-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7
ARG RAFTLAB_VERSION=0.0.0-dev
LABEL org.opencontainers.image.title="raftlab" \
      org.opencontainers.image.description="Raft consensus lab: simulator, CLI and a TCP key-value node" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${RAFTLAB_VERSION}"
RUN groupadd --system --gid 10001 raftlab \
    && useradd --system --uid 10001 --gid 10001 --home-dir /data --no-create-home --shell /usr/sbin/nologin raftlab \
    && mkdir -p /data \
    && chown 10001:10001 /data
COPY --from=build /src/raftlab-cli/build/install/raftlab /opt/raftlab
ENV PATH=/opt/raftlab/bin:${PATH}
USER 10001:10001
WORKDIR /data
VOLUME ["/data"]
EXPOSE 7000
ENTRYPOINT ["/opt/raftlab/bin/raftlab"]
CMD ["--help"]
