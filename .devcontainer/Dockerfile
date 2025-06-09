# Start from Ubuntu base
FROM ubuntu:22.04

# Set noninteractive mode for apt
ENV DEBIAN_FRONTEND=noninteractive

# Install core dependencies
RUN apt-get update && apt-get install -y \
  curl wget unzip git xz-utils zip libglu1-mesa openjdk-17-jdk \
  && apt-get clean

# Set up environment variables
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV FLUTTER_HOME=/opt/flutter
ENV PATH="$FLUTTER_HOME/bin:$FLUTTER_HOME/bin/cache/dart-sdk/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

# Install Android SDK Command Line Tools
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools \
  && cd $ANDROID_SDK_ROOT/cmdline-tools \
  && wget https://dl.google.com/android/repository/commandlinetools-linux-10406996_latest.zip -O tools.zip \
  && unzip tools.zip -d latest \
  && rm tools.zip

# Install required Android SDK components
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Install Flutter SDK
RUN git clone https://github.com/flutter/flutter.git -b stable --depth 1 $FLUTTER_HOME \
  && flutter doctor

# Pre-download Flutter dependencies
RUN flutter doctor \
  && flutter precache \
  && flutter config --enable-web \
  && flutter config --no-analytics

WORKDIR /app
