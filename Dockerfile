# Stage 1: Get jq + libs from Alpine
FROM alpine:3.21 AS alpine-jq
RUN apk add --no-cache jq

# Stage 2: Extract ginger binary
FROM ghcr.io/spice-labs-inc/ginger:latest AS ginger

# Final stage: Start from goatrodeo (already includes JVM)
FROM ghcr.io/spice-labs-inc/goatrodeo:latest

# Copy ginger binary
COPY --from=ginger /usr/bin/ginger /usr/bin/ginger

# Copy jq and its libraries from Alpine
COPY --from=alpine-jq /usr/bin/jq /usr/bin/jq
COPY --from=alpine-jq /lib/ /lib/
COPY --from=alpine-jq /usr/lib/ /usr/lib/

# Copy entrypoint script
COPY ./grind.sh /opt/grinder/grind.sh

ENTRYPOINT ["/opt/grinder/grind.sh"]
