# syntax=docker/dockerfile:1
#
# Local-build variant of Dockerfile for use with Apple's `container` CLI,
# whose builder does not implement the `secret` mount type used by the
# main Dockerfile (`RUN --mount=type=secret=...`). GITHUB_TOKEN is passed
# here as a plain ARG/ENV instead. This is only used in the discarded
# `build` stage - it never reaches the final runtime image's layers - but
# it does linger in this build stage's local layer cache, so prefer the
# real Dockerfile (with `docker buildx build --secret ...`) whenever
# Docker is available. See Dockerfile for the full multi-stage build notes.

# ---------------------------------------------------------------------------
# Stage 1: build the Kotlin app
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26-jdk AS build

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY morphe-fdroid-server/ .

# morphe-patcher is a normal Gradle dependency resolved from GitHub Packages,
# which requires an authenticated request even for public packages (see
# settings.gradle.kts).
#   container build \
#     --build-arg GITHUB_ACTOR=<your-github-username> \
#     --build-arg GITHUB_TOKEN=<your-github-token> \
#     -t custom-repo -f Dockerfile.local .
# GITHUB_ACTOR just needs to be *a* valid GitHub username for GHP's basic-auth
# check - it does not need any access to MorpheApp/morphe-patcher itself; any
# account's token works for reading a public package.
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN
ENV GITHUB_ACTOR=${GITHUB_ACTOR}
ENV GITHUB_TOKEN=${GITHUB_TOKEN}
RUN ./gradlew --no-daemon build -x test

# ---------------------------------------------------------------------------
# Stage 2: runtime image
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    nginx \
    supervisor \
    git \
    && rm -rf /var/lib/apt/lists/*

# fdroidserver: the only remaining external dependency, see header comment.
RUN pip3 install --no-cache-dir fdroidserver --break-system-packages

RUN mkdir -p /srv/fdroid/repo \
    /srv/fdroid/patched/repo \
    /srv/fdroid/metadata \
    /srv/fdroid/tmp \
    /srv/fdroid/patches \
    /app/db

COPY --from=build /build/app/build/libs/*-all.jar /app/app.jar

COPY nginx.conf /etc/nginx/sites-available/default
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

WORKDIR /srv/fdroid
EXPOSE 80

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
