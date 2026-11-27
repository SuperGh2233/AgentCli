FROM eclipse-temurin:21-jre-alpine@sha256:326837fba06a8ff5482a17bafbd65319e64a6e997febb7c85ebe7e3f73c12b11

LABEL org.opencontainers.image.title="Hao CLI Native AgentBench Candidate Worker"
LABEL org.opencontainers.image.description="Networkless runtime for a read-only mounted Hao CLI Candidate jar"
LABEL com.haocli.agentbench.role="candidate-worker"
LABEL com.haocli.agentbench.scope="relay-worker-only"

USER root
RUN addgroup -S -g 10002 haocliworker \
    && adduser -S -D -H -u 10002 -G haocliworker -h /home/haocli -s /sbin/nologin haocliworker \
    && mkdir -p /workspace /home/haocli /opt/haocli \
    && chown 10002:10002 /workspace /home/haocli \
    && chmod 0555 /opt/haocli

ENV HOME=/home/haocli \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /workspace
USER haocliworker:haocliworker

ENTRYPOINT ["java"]
CMD ["-version"]
