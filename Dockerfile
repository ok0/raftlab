# raftlab container image (charter R27). Build: docker build --build-arg RAFTLAB_VERSION=1.2.3 -t raftlab:1.2.3 .
FROM eclipse-temurin:21-jdk-noble@sha256:57424b1d8eca9c4cf8a4fe226207027911f08d6758628891bf59fcfb53777029 AS build
WORKDIR /src
COPY . .
ARG RAFTLAB_VERSION=0.0.0-dev
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :raftlab-cli:installDist "-Praftlab.version=${RAFTLAB_VERSION}"

FROM eclipse-temurin:21-jre-noble@sha256:cd35bb33859641c5374415826a5951845104093eaa401cbe9d3d9023444f1c13
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
