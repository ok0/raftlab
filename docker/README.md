# raftlab container usage

## Image

- Entrypoint: `raftlab` (the CLI start script), user `10001:10001`, volume `/data`, port `7000`.
- Build arg `RAFTLAB_VERSION` sets the version reported by `--version` (default `0.0.0-dev`).
- `JAVA_OPTS` and `RAFTLAB_OPTS` are honored by the start script.

```
docker build -t raftlab:local .
docker run --rm raftlab:local --help
docker run --rm raftlab:local --version
```

## Three-node cluster

`docker-compose.yml` runs `n1`, `n2` and `n3` on the compose network with internal port `7000`. No host ports are published. Cluster addresses are compose service names, so the client runs inside the network through the `kv` service (profile `client`).

## PAC6 demo

```
docker compose up --build -d
docker compose logs n1 n2 n3 | grep started
docker compose run --rm kv put greeting hello        # OK
docker compose run --rm kv get greeting              # hello
docker compose kill n2
docker compose run --rm kv put after-kill yes        # OK (n1+n3 are a majority)
docker compose run --rm kv get greeting              # hello
docker compose start n2
docker compose run --rm kv get after-kill            # yes
docker compose logs n2 | tail -n 5
docker compose down -v
```

Right after a kill, the first command may wait for re-election (bounded by `--timeout-millis`, default 10000).

Alternative client: `docker compose exec n1 raftlab kv --cluster n1=n1:7000,n2=n2:7000,n3=n3:7000 get greeting`.

## Using the published image

```
RAFTLAB_IMAGE=ghcr.io/<owner>/<repo>:X.Y.Z docker compose pull n1 n2 n3
RAFTLAB_IMAGE=ghcr.io/<owner>/<repo>:X.Y.Z docker compose up -d --no-build
```

## Graceful stop

`docker compose stop` sends SIGTERM. The node logs `raftlab node nX stopped` and exits 0.
