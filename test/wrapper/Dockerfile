FROM alpine:3.20
COPY entrypoint.sh /entrypoint.sh
# chmod + strip Windows CRLF in case git checks out with autocrlf
RUN chmod +x /entrypoint.sh && sed -i 's/\r$//' /entrypoint.sh

# Mock java command for runtime survey collect phase (RuntimeCollect).
RUN printf '#!/bin/sh\necho "===SPICE_TEST_BEGIN==="\nfor a in "$@"; do echo "ARG:$a"; done\necho "ENV:SPICE_PASS=${SPICE_PASS:-}"\necho "===SPICE_TEST_END==="\nexit 0\n' > /usr/local/bin/java && chmod +x /usr/local/bin/java

# Match real image layout: fat JAR + standalone agent + JFC
RUN mkdir -p /opt/spice-labs-cli && \
    echo mock > /opt/spice-labs-cli/spice-labs-cli.jar && \
    echo mock > /opt/spice-labs-cli/ancho.jar && \
    echo mock > /opt/spice-labs-cli/spice-jfr.jfc

ENTRYPOINT ["/entrypoint.sh"]
