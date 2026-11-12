FROM scratch
ARG VERSION
ARG SOURCE_COMMIT
LABEL org.opencontainers.image.source="https://github.com/guardiola98/sonora-android" \
      org.opencontainers.image.title="Sonora Android distribution" \
      org.opencontainers.image.description="Signed APK, matching GPL source, license and checksums. Distribution artifact; not a runnable Android container." \
      org.opencontainers.image.licenses="GPL-3.0" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$SOURCE_COMMIT"
COPY . /release/
