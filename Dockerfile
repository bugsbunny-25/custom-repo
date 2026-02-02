FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y \
    fdroidserver \
    python3 \
    python3-pip \
    python3-requests \
    python3-ruamel.yaml \
    curl \
    wget \
    unzip \
    openjdk-17-jdk-headless \
    nginx \
    supervisor \
    && rm -rf /var/lib/apt/lists/*

# Set up Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/34.0.0

# Download and install Android SDK
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmd-tools.zip && \
    unzip -q /tmp/cmd-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmd-tools.zip && \
    yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"

RUN pip3 install --no-cache-dir schedule --break-system-packages

# Create necessary directories
RUN mkdir -p /srv/fdroid/repo \
    /srv/fdroid/metadata \
    /srv/fdroid/tmp \
    /app

COPY update_checker.py init_repo.py /app/
COPY nginx.conf /etc/nginx/sites-available/default
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# Make the entrypoint executable
RUN chmod +x /app/docker-entrypoint.sh

# Set working directory
WORKDIR /srv/fdroid

# Expose port
EXPOSE 80

ENTRYPOINT ["/app/docker-entrypoint.sh"]

# Start supervisor
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]