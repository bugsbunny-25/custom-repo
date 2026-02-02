FROM debian:bookworm-slim

# Install dependencies
RUN apt-get update && apt-get install -y \
    fdroidserver \
    python3 \
    python3-pip \
    python3-requests \
    curl \
    wget \
    unzip \
    git \
    openjdk-17-jdk-headless \
    nginx \
    supervisor \
    && rm -rf /var/lib/apt/lists/*

# Set up Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/34.0.0

# Download and install Android SDK command line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    cd ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip commandlinetools-linux-11076708_latest.zip && \
    rm commandlinetools-linux-11076708_latest.zip && \
    mv cmdline-tools latest

# Accept licenses and install required SDK components
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-34"

# Install Python dependencies
RUN pip3 install --no-cache-dir requests ruamel.yaml schedule --break-system-packages

# Create necessary directories
RUN mkdir -p /srv/fdroid/repo \
    /srv/fdroid/metadata \
    /srv/fdroid/tmp \
    /var/log/supervisor \
    /app

# Copy application files
COPY update_checker.py /app/update_checker.py
COPY init_repo.py /app/init_repo.py
COPY nginx.conf /etc/nginx/sites-available/default
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Set working directory
WORKDIR /srv/fdroid

# Initialize the F-Droid repository
RUN python3 /app/init_repo.py

# Expose port
EXPOSE 80

# Start supervisor
CMD ["sh", "-c", "mkdir -p /var/log/supervisor && exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf"]