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
FROM eclipse-temurin:26-jdk

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    nginx \
    supervisor \
    git \
    wget \
    unzip \
    openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

# Android SDK build-tools: `fdroid update` shells out to `apksigner` to
# sign/verify modern (v2/v3) APK signature schemes. This was present in the
# pre-Kotlin-migration Dockerfile (debian:bookworm-slim + sdkmanager) but got
# dropped when the runtime base moved to eclipse-temurin; restore it here.
#
# The `sdkmanager` launcher script only officially supports JDK 17/11, so we
# install a side JDK 17 (openjdk-17-jdk-headless, above) just to run it
# during this provisioning step. `java` on PATH stays the base image's JDK
# 26 - required to run app.jar, which is compiled with jvmTarget=JVM_26 (see
# morphe-fdroid-server/app/build.gradle.kts) - so this doesn't affect the
# `java -jar /app/app.jar` command in supervisord.conf.
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/34.0.0

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmd-tools.zip && \
    unzip -q /tmp/cmd-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmd-tools.zip && \
    JAVA17_HOME=$(find /usr/lib/jvm -maxdepth 1 -type d -name 'java-17-openjdk*') && \
    JAVA_HOME=$JAVA17_HOME yes | sdkmanager --licenses && \
    JAVA_HOME=$JAVA17_HOME sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"

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
