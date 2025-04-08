FROM ghcr.io/spice-labs-inc/ginger:latest AS ginger
FROM ghcr.io/spice-labs-inc/goatrodeo:latest AS goatrodeo

COPY --from=ginger /usr/bin/ginger /usr/bin/ginger
COPY ./grind.sh /opt/grinder/grind.sh


ENTRYPOINT ["/opt/grinder/grind.sh"]
