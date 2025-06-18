# Base image: Start from goatrodeo
FROM ghcr.io/spice-labs-inc/goatrodeo:latest

# Copy ginger binary directly from ginger image
COPY --from=spicelabs/ginger:latest /usr/bin/ginger /usr/bin/ginger

# Copy spicelabs.sh into the final image
COPY ./spice-labs.sh /opt/spice-labs-cli/spice-labs.sh
COPY ./spice /opt/spice-labs-cli/spice
COPY ./spice /opt/spice-labs-cli/spice.ps1

ENTRYPOINT ["/opt/spice-labs-cli/spice-labs.sh"]
