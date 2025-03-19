FROM spicelabs/ginger AS ginger
FROM spicelabs/goatrodeo

#COPY --from=goatrodeo /opt/docker/lib/goatrodeo.goatrodeo-0.6.3.jar /goatrodeo.jar
COPY --from=ginger /usr/bin/ginger /usr/bin/ginger
COPY ./grind.sh /usr/bin/grind.sh

ENTRYPOINT ["/usr/bin/grind.sh"]