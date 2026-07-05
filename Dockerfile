# syntax=docker/dockerfile:1
#
# Multi-stage build: the app itself (admin server, GitHub/APKMirror
# scrapers, patch pipeline) is now 100% Kotlin (see morphe-fdroid-server/),
# calling morphe-patcher directly instead of shelling out to a CLI. The only
# remaining external dependency is `fdroidserver` (the `fdroid` CLI) - a
# separate, actively-maintained Python project from the F-Droid
# organization that generates/signs the repo index; we intentionally don't
# reimplement it (see morphe-fdroid-server/plan.md §8), so Python still
# appears in the final image, but only as that one unmodified third-party
# tool - none of *this project's* application logic is Python anymore.

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
# settings.gradle.kts). The token is passed in as a BuildKit secret (never a
# plain ARG/ENV - those get baked into the image's build history/layers):
#   DOCKER_BUILDKIT=1 docker build \
#     --secret id=github_token,env=GITHUB_TOKEN \
#     --build-arg GITHUB_ACTOR=<your-github-username> \
#     -t custom-repo .
# GITHUB_ACTOR just needs to be *a* valid GitHub username for GHP's basic-auth
# check - it does not need any access to MorpheApp/morphe-patcher itself; any
# account's token works for reading a public package. In CI, the workflow's
# own `secrets.GITHUB_TOKEN` + `github.actor` are used instead - see
# .github/workflows/docker-publish.yml.
ARG GITHUB_ACTOR
ENV GITHUB_ACTOR=${GITHUB_ACTOR}
RUN --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    ./gradlew --no-daemon build -x test

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
