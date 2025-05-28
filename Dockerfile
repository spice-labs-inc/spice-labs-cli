# Base image: Start from goatrodeo
FROM ghcr.io/spice-labs-inc/goatrodeo:latest

# Copy ginger binary directly from ginger image
COPY --from=ghcr.io/spice-labs-inc/ginger:latest /usr/bin/ginger /usr/bin/ginger

# Copy spicelabs.sh into the final image
COPY ./spice-labs.sh /opt/spice-labs-cli/spice-labs.sh

ENTRYPOINT ["/opt/spice-labs-cli/spice-labs.sh"]
