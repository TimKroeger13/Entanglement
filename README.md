# Entanglement

**Entangled Markdown files – edit one, and all its copies follow.**

Entanglement keeps text files in sync between your PCs and your Android phone. Every device keeps a real local
copy, so you can keep writing offline (on the train, in a tunnel) and the copies sync as soon as there is a
connection again. A tiny self-hosted server sits in the middle; plain text needs almost no storage.

> Status: work in progress. The sync core and the server are done; the Windows and Android apps are next.

## Vocabulary

| In the app | Meaning |
|---|---|
| **Entangle** | Start syncing a file. It stays where it is on your PC. |
| **Decohere** | Stop syncing a file. Local copies are kept. |
| **Observer** | One of your devices (e.g. "Desktop", "Laptop", "Phone"). |
| **Unobserved** | Entangled elsewhere, but not yet placed on this device. |
| **Superposition** | The same lines were changed differently on two devices. Both versions are kept in the file. |
| **Measure** | Resolve a superposition by keeping one version – it collapses. |
| **Lost** | The local file was moved or deleted. |

### How changes are combined

Each device remembers the last version it agreed on with the server. If two devices changed a file while offline,
their changes are combined with a three-way merge: edits on different lines merge automatically. Only when the
same lines were changed on both does the file end up in a superposition:

```
⚛⚛⚛ Superposition – Laptop
Scene 2: Fog on the window.
⚛⚛⚛ ─────────── Phone
Scene 2: Hail on the window.
⚛⚛⚛ End
```

Delete the version you don't want together with the three marker lines, and the superposition collapses.
No text is ever thrown away. The server also keeps the last 50 versions of every file.

## Server setup

Requirements: Docker with Compose, and Nginx in front of it for HTTPS.

The server image is built by GitHub Actions and published to
`ghcr.io/timkroeger13/entanglement-server`, so nothing is compiled on the server
(compiling needs more RAM than a small VPS has). Only `docker-compose.yml` and `.env` are needed there:

```sh
git clone https://github.com/TimKroeger13/Entanglement.git entanglement
cd entanglement
cp .env.example .env
openssl rand -hex 32          # paste the output as ENTANGLEMENT_TOKEN in .env
docker compose pull
docker compose up -d
curl http://127.0.0.1:8080/health   # -> ok
```

The running container needs about 150–200 MB of RAM and the image about 250 MB of disk.
Data lives in the Docker volume `entanglement-data` (one SQLite file).

If you fork this, change the `image:` line in `docker-compose.yml` to your own GitHub account.

### Nginx

Point a subdomain at your server and add a site like this (certificate e.g. via certbot):

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name entanglement.example.com;

    ssl_certificate     /etc/letsencrypt/live/entanglement.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/entanglement.example.com/privkey.pem;

    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

The apps then connect to `https://entanglement.example.com` with the token from `.env`.

### Update

Every push to `main` that touches the server builds a new image. On the server:

```sh
git pull                 # only needed if docker-compose.yml changed
docker compose pull
docker compose up -d
docker image prune -f    # remove the previous image
```

### Backup

```sh
docker compose cp entanglement:/data ./entanglement-backup
```

## API

All routes except `/health` require `Authorization: Bearer <ENTANGLEMENT_TOKEN>`.

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/health` | Liveness check |
| `PUT` | `/api/observers/{id}` | Register or rename a device |
| `GET` | `/api/docs` | All documents with version and paths per observer (no content) |
| `POST` | `/api/docs` | Entangle a new document (idempotent per id) |
| `GET` | `/api/docs/{id}` | Document with content |
| `PUT` | `/api/docs/{id}` | Push a new version; `409` with the current document if `baseVersion` is outdated |
| `PUT` | `/api/docs/{id}/placement` | Set where the document lives on an observer |
| `DELETE` | `/api/docs/{id}` | Decohere |

## Development

Kotlin 2.4, JDK 21.

```sh
./gradlew test                   # all tests
./gradlew :server:run            # needs ENTANGLEMENT_TOKEN in the environment
docker build -t entanglement-server .   # build the image locally (needs ~2 GB RAM)
```

| Module | Content |
|---|---|
| `shared` | Models, three-way merge, sync engine, HTTP client – used by all others |
| `server` | Ktor + SQLite |
| `desktopApp` | Windows tray app *(coming)* |
| `androidApp` | Android app with editor *(coming)* |
