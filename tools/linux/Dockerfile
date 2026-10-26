FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e

RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-jsonschema clang libclang-rt-18-dev \
      libcurl4-openssl-dev libxml2-dev libssl-dev zlib1g-dev \
      curl ca-certificates xz-utils unzip git locales \
    && rm -rf /var/lib/apt/lists/*

RUN curl --fail --location --retry 3 \
      https://github.com/JetBrains/kotlin/releases/download/v2.4.20/kotlin-native-prebuilt-linux-x86_64-2.4.20.tar.gz \
      --output /tmp/kotlin-native.tar.gz \
    && echo '32d33c15c4da668a8d47a0ce2aea95be2c2cf8f5d1d65f6f4ed3697e4686dc7f  /tmp/kotlin-native.tar.gz' | sha256sum --check \
    && tar -xzf /tmp/kotlin-native.tar.gz -C /opt \
    && rm /tmp/kotlin-native.tar.gz

ENV KONAN_HOME=/opt/kotlin-native-prebuilt-linux-x86_64-2.4.20
RUN curl --fail --location --retry 3 \
      https://github.com/buchgr/bazel-remote/releases/download/v2.6.4/bazel-remote-2.6.4-linux-amd64 \
      --output /usr/local/bin/bazel-remote \
    && echo '6a6b64666c2238ec6b49b7b29f5e41e4149de09417a0028897c9bffe40376876  /usr/local/bin/bazel-remote' | sha256sum --check \
    && chmod 755 /usr/local/bin/bazel-remote
ENV PYTHONDONTWRITEBYTECODE=1
WORKDIR /work/kvik
