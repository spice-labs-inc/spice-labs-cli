FROM alpine:3.20
COPY entrypoint.sh /entrypoint.sh
# chmod + strip Windows CRLF in case git checks out with autocrlf
RUN chmod +x /entrypoint.sh && sed -i 's/\r$//' /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
