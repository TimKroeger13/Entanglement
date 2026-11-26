# --- Build stage: plain Go, standard library only. -p 1 keeps peak RAM low on small servers. ---
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -p 1 -trimpath -ldflags="-s -w" -o /out/entanglement-server . \
    && mkdir -p /out/data

# --- Runtime: nothing but the binary (~8 MB image) ---
FROM scratch
COPY --from=build /out/entanglement-server /entanglement-server
COPY --from=build --chown=65532:65532 /out/data /data
USER 65532:65532
ENV ENTANGLEMENT_DATA=/data \
    PORT=8080 \
    GOMEMLIMIT=48MiB
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s --start-period=5s CMD ["/entanglement-server", "-healthcheck"]
ENTRYPOINT ["/entanglement-server"]
