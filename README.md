# DriveSync

A personal cross-device file sync tool: two laptops each watch a folder, and
changes on one are synced to the other through a small coordinator server.
If the same file gets edited on both devices while offline, DriveSync detects
the conflict and lets you resolve it instead of silently losing data.

Built after a laptop hardware failure caused loss of access to files that
only lived on that one machine — DriveSync exists to make that specific
failure mode impossible.

> Status: v1 complete — all 7 build milestones done and verified end to
> end (scaffolding, watcher + hashing, coordinator REST API, real-time
> WebSocket sync, conflict detection & resolution, live dashboard, and a
> full local two-device test covering live sync, offline/reconnect, and a
> deliberate conflict). See [LEARNING.md](LEARNING.md) for a running log of
> what's built, why, and how it works. Phase 2 (cloud deployment, a real
> internet-based two-device test, more than two devices) is not started.

## Project layout
- [`server/`](server/) — Spring Boot 3 coordinator server (Java 17, Maven).
  Stores file metadata (paths, hashes, versions, device state) in Postgres.
  Never stores raw file content.
- [`watcher-client/`](watcher-client/) — Java NIO watcher that runs on each
  device, watches a local folder, hashes changed files, and talks to the
  coordinator server.
- [`web/`](web/) — React (Vite) dashboard: device status, activity feed,
  conflict resolution.

## Prerequisites
- Java 17+ (backend and watcher client use the Maven Wrapper, so a separate
  Maven install isn't needed)
- Node.js LTS (frontend)
- PostgreSQL running locally, with a database and role created for the app
  (see below)

## Local setup

### 1. Database
Create a dedicated database and role (don't use the Postgres superuser for
the app):

```sql
CREATE ROLE drivesync_app LOGIN PASSWORD 'drivesync_app_pw';
CREATE DATABASE drivesync OWNER drivesync_app;
```

### 2. Backend (coordinator server)
```bash
cd server
./mvnw spring-boot:run
```
Runs on `http://localhost:8080` by default. Config is externalized via env
vars (see `server/src/main/resources/application.yml`) — override
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT` as needed.

### 3. Frontend (dashboard)
```bash
cd web
npm install
npm run dev
```
Runs on `http://localhost:5173`, and proxies `/api` and `/ws` requests to
the backend automatically in dev.

### 4. Watcher client(s)
Build the watcher once:
```bash
cd watcher-client
./mvnw package -DskipTests
```
Then run one instance per device you want to simulate, each pointed at its
own folder and with its own device ID:
```bash
java -jar target/drivesync-watcher.jar --folder ../sync-folder-a --device-id laptop-a
java -jar target/drivesync-watcher.jar --folder ../sync-folder-b --device-id laptop-b
```
Options (all optional except `--folder`): `--device-id`, `--server`
(coordinator base URL, default `http://localhost:8080`), `--debounce-ms`
(default `800`). The same options can be set via `WATCH_FOLDER`,
`DEVICE_ID`, `SERVER_ADDRESS`, `DEBOUNCE_MS` env vars instead of flags.

A running watcher fully syncs with any other watcher pointed at the same
coordinator: local changes are uploaded and pushed live over WebSocket to
other connected devices, changes made while this device wasn't running are
fetched via REST catch-up on startup, and a conflicting independent edit to
the same file is detected and resolved automatically (both versions kept —
see below).

## Coordinator REST API
- `POST /api/devices/register` — `{deviceId, displayName?}` → upserts and returns the device.
- `GET /api/devices` — list all known devices, including live `online` status.
- `POST /api/sync/changes` — `{deviceId, relativePath, changeType, contentHash, sizeBytes, clientDetectedAt}` → records a change (or returns `duplicate: true` if nothing actually changed server-side).
- `GET /api/sync/changes?since=<id>&excludeDevice=<id>` — catch-up fetch: everything after the given change id, excluding the caller's own changes.
- `GET /api/sync/activity?limit=50` — recent changes, newest first (what the dashboard's activity feed shows).
- `PUT /api/sync/blobs/{sha256}` — upload file content (raw bytes), addressed by its hash.
- `GET /api/sync/blobs/{sha256}` — download file content by hash.
- `HEAD /api/sync/blobs/{sha256}` — check whether the server already has this content (200/404).
- `GET /api/conflicts` — list detected conflicts (optional `?resolved=true|false` filter).
- `POST /api/conflicts/{id}/resolve` — `{keepDeviceId}` → manually override which device's version keeps the original filename (the other stays as the renamed backup, never deleted).

## Real-time sync (WebSocket/STOMP)
- Endpoint: `ws://localhost:8080/ws?deviceId=<yourDeviceId>`
- Subscribe to `/topic/changes` for live-pushed file changes, and
  `/topic/devices` for live device online/offline updates.
- The watcher client connects automatically; the query param `deviceId` is
  what lets the server track per-device connect/disconnect for the
  dashboard's device status panel.

## Testing two-device sync locally
1. Start the backend (see above) and confirm `curl http://localhost:8080/api/health`.
2. Build the watcher: `cd watcher-client && ./mvnw package -DskipTests`.
3. In two separate terminals, run:
   ```bash
   java -jar target/drivesync-watcher.jar --folder ../sync-folder-a --device-id laptop-a
   java -jar target/drivesync-watcher.jar --folder ../sync-folder-b --device-id laptop-b
   ```
4. Create/edit/delete a file in `sync-folder-a` and watch it appear/update/
   disappear in `sync-folder-b` within about a second (the default debounce
   window), and vice versa.
5. **Offline/reconnect test**: stop one watcher (Ctrl+C), make changes on
   the other device, then restart the stopped watcher — it fetches everything
   it missed via catch-up on startup, before it starts watching locally.
6. **Conflict test**: with both watchers running and already in sync on a
   shared file, edit that same file with different content on both devices
   within about a second of each other (faster than the debounce window).
   Both devices should converge to identical final state: the later edit
   keeps the original filename everywhere, and the earlier edit is
   preserved, byte-for-byte, as `name (conflicted copy - device).ext` on
   both devices — never deleted. Check `GET /api/conflicts` to see it
   recorded, and `POST /api/conflicts/{id}/resolve` with `{"keepDeviceId":
   "..."}` to manually pick the other version instead.
7. **Dashboard**: open `http://localhost:5173` while watchers are running —
   device status, the activity feed, and any conflicts update live with no
   page refresh (backed by the same WebSocket topics the watchers use).
