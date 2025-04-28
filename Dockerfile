# Base image: Start from goatrodeo
FROM ghcr.io/spice-labs-inc/goatrodeo:latest

# Copy ginger binary directly from ginger image
COPY --from=ghcr.io/spice-labs-inc/ginger:latest /usr/bin/ginger /usr/bin/ginger

# Copy grind.sh into the final image
COPY ./grind.sh /opt/grinder/grind.sh

ENTRYPOINT ["/opt/grinder/grind.sh"]
