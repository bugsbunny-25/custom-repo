# syntax=docker/dockerfile:1
#
# The app itself (admin server, GitHub/APKMirror scrapers, patch pipeline) is
# 100% Kotlin (see morphe-fdroid-server/), calling morphe-patcher directly.
# The only remaining external dependency is `fdroidserver` (the `fdroid` CLI) -
# a separate, actively-maintained Python project from the F-Droid organization
# that generates and signs the repo index; we intentionally don't reimplement
# it (see morphe-fdroid-server/plan.md §8), so Python still appears in the
# final image, but only as that one unmodified third-party tool.
#
# ---------------------------------------------------------------------------
# What the runtime actually needs, and why this is NOT a plain JRE
# ---------------------------------------------------------------------------
# `fdroid update` signs the index with two *different* tools, measured against
# fdroidserver 2.4.5 (fdroidserver/index.py, fdroidserver/signindex.py):
#
#   index.xml   -> index.jar      `jar cf` + `jarsigner`  (SHA1withRSA)
#   index-v1.json -> index-v1.jar `jarsigner`             (SHA1withRSA)
#   entry.json  -> entry.jar      `apksigner`             (modern algorithms)
#
# The two SHA1 ones are deliberate - F-Droid keeps them on SHA1 so old Android
# versions can still verify the index (signindex.sign_jar(use_old_algs=True)).
#
# `jar` and `jarsigner` live in the JDK's `jdk.jartool` module, which is NOT
# present in `eclipse-temurin:*-jre`. (`keytool`, which `fdroid init` needs to
# create keystores, IS in `java.base` and so does survive in a JRE - which is
# what makes this trap easy to fall into.) On a JRE-only image `fdroid update`
# fails with:
#
#   CRITICAL: OSError while trying to execute jar cf index.jar index.xml:
#             [Errno 2] No such file or directory: 'jar'
#
# and writes no index at all. That failure happens inside the running app, not
# at container start, so the HEALTHCHECK below plus `podman auto-update`
# rollback would NOT catch it: the container stays up serving a stale index
# while clients silently stop seeing updates.
#
# So instead of a JRE we `jlink` a custom runtime that keeps `jdk.jartool`.
# That is both correct and much smaller than the full JDK the image used to
# ship (measured: 65 MB vs 374 MB).
#
# Equally, almost none of the Android SDK is needed. fdroidserver 2.4.5 has
# zero references to `zipalign`, and `fdroid update` completes with no
# `aapt`/`aapt2` present at all (androguard parses APKs in Python). The only
# Android SDK artifact required at runtime is `apksigner.jar`, about 1 MB -
# so the SDK install (cmdline-tools + sdkmanager + platform-tools +
# build-tools + platforms;android-34, and the side JDK 17 that sdkmanager
# needed) is gone entirely, replaced by one checksum-pinned zip extract.

ARG JAVA_VERSION=26
ARG FDROIDSERVER_VERSION=2.4.5
ARG ANDROID_BUILD_TOOLS=34.0.0

# ---------------------------------------------------------------------------
# Stage 1: build the Kotlin app
# ---------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build

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
# Stage 2: jlink a minimal Java runtime that still has `jar` + `jarsigner`
# ---------------------------------------------------------------------------
# Module set = what `jdeps --print-module-deps` reports for the shadow jar,
# plus the tool/crypto modules that are resolved reflectively and so never
# show up in a static scan:
#
#   jdk.jartool  - `jar` and `jarsigner`, required by fdroidserver (above).
#                  This is the whole reason this is not a `-jre` base.
#   jdk.crypto.ec / jdk.crypto.cryptoki
#                - EC cipher suites for outbound TLS (GitHub, APKMirror,
#                  APKPure) and PKCS#11; both are reflective JCA providers.
#   jdk.localedata
#                - non-root locales, trimmed to English only. APKMirror
#                  version strings are parsed with locale-sensitive
#                  formatting, so the root locale alone is not enough.
#
# `keytool` comes from java.base and so is always present.
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS javaruntime

RUN jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.sql,jdk.jfr,jdk.unsupported,jdk.jartool,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata \
      --include-locales=en \
      --output /opt/java-runtime \
      --no-header-files \
      --no-man-pages \
      --strip-debug \
      --compress zip-6 \
 && /opt/java-runtime/bin/java -version \
 && test -x /opt/java-runtime/bin/jar \
 && test -x /opt/java-runtime/bin/jarsigner \
 && test -x /opt/java-runtime/bin/keytool

# ---------------------------------------------------------------------------
# Stage 3: extract apksigner.jar from Android build-tools
# ---------------------------------------------------------------------------
# Pinned by SHA-256 rather than fetched through `sdkmanager`. Google's own
# sdkmanager launcher only supports JDK 17/11, which is the only reason this
# image ever carried a second JDK; the `sdkmanager` PyPI package (an
# fdroidserver dependency) avoids that but needs `gpgv` to verify Google's
# repository manifest. A pinned zip needs neither and is reproducible.
#
# The SHA-1 below is the value Google publishes for this archive in
# https://dl.google.com/android/repository/repository2-3.xml; the SHA-256 is
# of that same verified download and is what we actually enforce.
FROM debian:trixie-slim AS apksigner

ARG ANDROID_BUILD_TOOLS
ARG BUILD_TOOLS_ZIP=build-tools_r34-linux.zip
ARG BUILD_TOOLS_SHA256=e858c4b60069d0431051b225d384413b1643e1289b00a4825aed347f25bd510f

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://dl.google.com/android/repository/${BUILD_TOOLS_ZIP}" -o /tmp/bt.zip \
 && echo "${BUILD_TOOLS_SHA256}  /tmp/bt.zip" | sha256sum -c - \
 && unzip -q -j /tmp/bt.zip 'android-14/lib/apksigner.jar' -d /out \
 && rm /tmp/bt.zip \
 && test -s /out/apksigner.jar

# ---------------------------------------------------------------------------
# Stage 4: runtime image
# ---------------------------------------------------------------------------
FROM debian:trixie-slim

ARG FDROIDSERVER_VERSION

ENV JAVA_HOME=/opt/java-runtime
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=/opt/java-runtime/bin:/opt/android-sdk/build-tools/bin:${PATH}

# git: not optional. fdroidserver imports GitPython at module import time and
#   dies with "Bad git executable" without a `git` binary on PATH.
# libmagic1: python-magic/puremagic file type detection.
# curl: used by the HEALTHCHECK below.
# nginx: serves the repo and proxies the admin UI/API (see nginx.conf).
# tzdata: the quadlet passes TZ=America/New_York.
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 \
      python3-pip \
      libmagic1 \
      ca-certificates \
      git \
      curl \
      nginx \
      tzdata \
    && rm -rf /var/lib/apt/lists/*

# fdroidserver, pinned.
#
# Note on a trap that is easy to fall into here: `fdroid init` has a fallback
# path that does `from pkg_resources import get_distribution`, and setuptools
# 81+ dropped pkg_resources (84.x does not ship it at all), so that path would
# now raise ModuleNotFoundError *after* creating repo/, leaving a
# half-initialized repo with no config.yml. It is not reachable in this image:
# the fallback only runs when the packaged examples/config.yml cannot be
# found, and `get_examples_dir()` (fdroidserver/common.py) locates it from
# `sys.argv[0]` - which, for the `fdroid` console script, is the absolute
# path /usr/local/bin/fdroid that the shebang hands to Python. That resolves
# to /usr/local/share/doc/fdroidserver/examples, which this install does have.
# Verified by running a real `fdroid init` in the built image.
#
# So setuptools is deliberately NOT installed to prop that path up - pinning
# an old setuptools for a branch that never executes would be carrying a
# deprecated dependency for nothing. What does guard it is the smoke test
# (scripts/smoke-test.sh), which runs `fdroid init` on an empty directory and
# fails if config.yml does not appear.
#
# Then remove the dependencies that `fdroid init` and `fdroid update` never
# import. Measured by tracing sys.modules across a real init + update run
# against a copy of the production repo: of 244 MB of site-packages only
# ~75 MB is ever touched. Everything dropped here belongs to fdroidserver
# subcommands this project does not use - `fdroid deploy` (cloud/SSH upload),
# `fdroid build` (Vagrant build VMs), and its interactive IPython shell.
#
# androguard's own deps (networkx) and the requests stack are deliberately
# KEPT even though the traced run did not import them, because androguard
# reaches for them lazily on APK shapes this test did not cover.
#
# If a future fdroidserver starts needing one of these, the repo smoke test
# (scripts/smoke-test.sh) fails loudly on a real `fdroid init` + `fdroid
# update` rather than failing in production.
RUN pip3 install --no-cache-dir --break-system-packages \
      "fdroidserver==${FDROIDSERVER_VERSION}" \
 && pip3 uninstall --yes --break-system-packages \
      apache-libcloud \
      SQLAlchemy alembic Mako dataset \
      ipython jedi parso Pygments ipython-pygments-lexers prompt_toolkit \
      wcwidth traitlets stack_data pure-eval asttokens executing \
      matplotlib-inline pexpect ptyprocess \
      paramiko PyNaCl \
      python-vagrant invoke \
      psutil pydot oscrypto yamllint \
    ; pip3 cache purge 2>/dev/null; true

# apksigner: our own launcher, not Google's. Google's build-tools script is a
# bash wrapper that ends in `exec java $javaOpts -jar apksigner.jar "$@"` and
# offers no way to inject JVM flags when it is invoked by fdroidserver (which
# passes no `-J` options). We need `--enable-native-access=ALL-UNNAMED`
# because apksigner's bundled conscrypt calls System::loadLibrary, which
# already warns on Java 26 ("Restricted methods will be blocked in a future
# release") and would become a hard failure later - taking index-v2 signing
# with it. Google's default -Xmx1024M is kept, overridable via APKSIGNER_XMX.
COPY --from=apksigner /out/apksigner.jar /opt/android-sdk/build-tools/lib/apksigner.jar
RUN mkdir -p /opt/android-sdk/build-tools/bin \
 && printf '%s\n' \
      '#!/bin/sh' \
      '# Minimal replacement for Google'"'"'s build-tools apksigner launcher.' \
      '# See the Dockerfile for why this is not the stock script.' \
      'exec java \' \
      '  --enable-native-access=ALL-UNNAMED \' \
      '  "-Xmx${APKSIGNER_XMX:-1024M}" \' \
      '  -jar /opt/android-sdk/build-tools/lib/apksigner.jar "$@"' \
      > /opt/android-sdk/build-tools/bin/apksigner \
 && chmod +x /opt/android-sdk/build-tools/bin/apksigner

COPY --from=javaruntime /opt/java-runtime /opt/java-runtime

# Repo layout. `/keys` is a separate mount point from `/srv/fdroid` so the
# small must-be-backed-up material (keystores) can live on a backed-up disk
# while the bulk rebuildable data (published APKs, archive, caches) does not.
# See docs/integration-spec.md for the migration.
RUN mkdir -p /srv/fdroid/repo \
    /srv/fdroid/patched/repo \
    /srv/fdroid/patched-tv/repo \
    /srv/fdroid/metadata \
    /srv/fdroid/tmp \
    /srv/fdroid/patches \
    /srv/fdroid/patches-tv \
    /srv/fdroid/.requests \
    /srv/fdroid/.status \
    /app/db \
    /keys

COPY --from=build /build/app/build/libs/*-all.jar /app/app.jar

COPY nginx.conf /etc/nginx/sites-available/default

# nginx defaults to `worker_processes auto`, i.e. one worker per CPU - five on
# the machine this was measured on, for a server whose job is handing out APK
# files to a handful of phones. Two is ample and keeps the always-on
# footprint down, which is the whole point of splitting serving from building.
RUN sed -i 's/^worker_processes .*/worker_processes 2;/' /etc/nginx/nginx.conf \
 && grep -q '^worker_processes 2;' /etc/nginx/nginx.conf
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

WORKDIR /srv/fdroid
EXPOSE 80

# Proves both halves of the always-on container are alive, which a plain
# `/health` check cannot: `/health` is answered by nginx itself and returns
# 200 even when the JVM behind /admin and /api/ is wedged. The app's own
# /api/health is served by the JVM, so a hung JVM now marks the container
# unhealthy - which is what makes `podman auto-update`'s rollback actually
# able to reject a bad build.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1/health >/dev/null \
      && curl -fsS http://127.0.0.1/api/health >/dev/null

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["server"]
