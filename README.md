# Entanglement

**Entangled Markdown files – edit one, and all its copies follow.**

Entanglement keeps text files in sync between your PCs and your Android phone. Every device keeps a real local
copy, so you can keep writing offline (on the train, in a tunnel) and the copies sync as soon as there is a
connection again. A tiny self-hosted server sits in the middle; plain text needs almost no storage.

> Status: work in progress. Server and Windows app work; the Android app is next.

## Vocabulary

| In the app | Meaning |
|---|---|
| **Entangle** | Start syncing a file. It stays where it is on your PC. |
| **Desync** | Stop syncing a file on all devices. Every copy is kept; **Re-sync** joins them again and merges what changed meanwhile. |
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

The server is a single Go program using only the standard library. It builds on the server itself in about
a minute and fits comfortably on a small VPS: the image is about 8 MB and the container uses roughly 10–20 MB of RAM.

```sh
git clone https://github.com/TimKroeger13/Entanglement.git entanglement
cd entanglement
cp .env.example .env
openssl rand -hex 32          # paste the output as ENTANGLEMENT_TOKEN in .env
docker compose up -d --build
curl http://127.0.0.1:8423/health   # -> ok
docker image rm golang:1.27-alpine && docker builder prune -f   # free the Go build image (~300 MB)
```

Data lives in the Docker volume `entanglement-data` as plain files:

```
state.json               devices, document names, versions, paths per device
docs/<id>/<version>.md   every kept version as a normal Markdown file
```

### Nginx

Point a subdomain at your server and add a site like this (certificate e.g. via certbot):

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name entanglement.example.com;

    ssl_certificate     /etc/letsencrypt/live/entanglement.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/entanglement.example.com/privkey.pem;

    client_max_body_size 16m;

    location / {
        proxy_pass http://127.0.0.1:8423;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

The apps then connect to `https://entanglement.example.com` with the token from `.env`.

Or, without a subdomain, under a path of an existing site (inside its `server { listen 443 ... }` block):

```nginx
location ^~ /entanglement/ {
    proxy_pass http://127.0.0.1:8423/;   # trailing slash strips the /entanglement prefix
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

The server URL in the apps is then `https://example.com/entanglement`.

### Update

```sh
git pull
docker compose up -d --build
docker image prune -f                                    # old entanglement image
docker image rm golang:1.27-alpine && docker builder prune -f
```

### Backup

```sh
docker compose cp entanglement:/data ./entanglement-backup
```

## Windows app

A tray app. It keeps your entangled files where they are and syncs them whenever you save – edit them in any
editor (VS Code, Obsidian, Notepad…).

```sh
./gradlew :desktopApp:packageMsi
# -> desktopApp/build/compose/binaries/main/msi/Entanglement-1.0.0.msi (bundles its own Java runtime)
```

After installing, open **Settings** and enter the server URL, the token from `.env` and a name for this PC.
There you can also enable:

- **“Entangle” in the Explorer context menu** for `.md` files (Windows 11: under “Show more options”)
- **Start with Windows** (runs in the tray)

Every entangled file shows where it lives on each observer. **Rename…**, **Move…** and **Desync…** are in the
*More* menu; renaming a file in Explorer or an editor is noticed and followed. A file entangled on another device appears as
*unobserved* – **Place here…** puts a copy on this PC (an existing file at that spot is merged, never overwritten).
Settings and state live in `%APPDATA%\Entanglement`.

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
| `PUT` | `/api/docs/{id}/name` | Rename (display name; PCs rename their file if it still has the old name) |
| `DELETE` | `/api/docs/{id}` | Desync (the document stays, marked as desynced) |
| `POST` | `/api/docs/{id}/resync` | Undo a desync |

## Development

Server: Go 1.24+, standard library only. Apps: Kotlin 2.4, JDK 21.

```sh
cd server && go test ./...       # server tests
./gradlew test                   # merge, sync engine and desktop tests
./gradlew :desktopApp:run        # start the Windows app from source

# Contract test: the Kotlin client against a running Go server
cd server && ENTANGLEMENT_TOKEN=local-test-token-123456789 PORT=18081 go run .
ENTANGLEMENT_TEST_URL=http://127.0.0.1:18081 ENTANGLEMENT_TEST_TOKEN=local-test-token-123456789 ./gradlew :shared:test
```

| Module | Content |
|---|---|
| `server` | Go server, files instead of a database |
| `shared` | Kotlin: models, three-way merge, sync engine, HTTP client – used by both apps |
| `desktopApp` | Windows tray app (Compose Desktop) |
| `androidApp` | Android app with editor *(coming)* |
