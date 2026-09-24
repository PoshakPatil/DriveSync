# DriveSync — Learning Log

This file is a running record of what was built, why, and how the core
mechanics work. It's written to be read on its own, without needing to
re-read all the source code.

---

## Milestone 1 — Project scaffolding

### What was built
- **Backend**: a Spring Boot 3.3.5 / Java 17 project at [server/](server/), using
  the Maven Wrapper (`mvnw`/`mvnw.cmd`) so no system-wide Maven install is
  needed — running `./mvnw spring-boot:run` downloads the right Maven version
  automatically the first time.
  - [DriveSyncServerApplication.java](server/src/main/java/com/drivesync/server/DriveSyncServerApplication.java) — entry point.
  - [HealthController.java](server/src/main/java/com/drivesync/server/controller/HealthController.java) — `GET /api/health`, used to prove the frontend can reach the backend.
  - [WebConfig.java](server/src/main/java/com/drivesync/server/config/WebConfig.java) — CORS config for local dev.
  - [application.yml](server/src/main/resources/application.yml) — all config (DB URL, credentials, port) comes from environment variables with local-dev defaults, so nothing is hardcoded.
- **Frontend**: a React app scaffolded with Vite at [web/](web/), with a
  [vite.config.js](web/vite.config.js) dev proxy so the browser can call `/api/...`
  without CORS or hardcoding `localhost:8080` anywhere in the React code.
- **Database**: PostgreSQL 17 installed natively as a Windows service. A
  dedicated `drivesync` database and `drivesync_app` role were created —
  the app never connects as the Postgres superuser, only as a low-privilege
  role scoped to its own database.

### Why these decisions
- **Maven Wrapper instead of installing Maven globally**: Maven isn't
  distributed via winget on Windows, and pinning the exact Maven version per
  project (via `.mvn/wrapper/maven-wrapper.properties`) means the build is
  reproducible regardless of what's installed on a given machine — this
  matters more once there are two client devices involved in later milestones.
- **Vite dev proxy instead of a hardcoded backend URL**: keeps the frontend
  origin-agnostic. The React code just calls `/api/health`; whether that's
  proxied to `localhost:8080` in dev or served from the same origin in a
  future production build is a config concern, not a code concern.
- **A dedicated Postgres role instead of using the superuser**: least
  privilege. If the app's credentials ever leaked, they only grant access to
  the `drivesync` database, not the whole Postgres instance.
- **Hibernate `ddl-auto: update` instead of Flyway/Liquibase**: this is a
  learning project with one developer and no deployed environments to keep in
  sync, so a migrations framework would add ceremony without adding much
  understanding. A real production system would use versioned migrations
  instead, since `ddl-auto: update` can't safely handle destructive schema
  changes (renaming/dropping columns) — worth knowing as a limitation, not
  just a fact.

### How the pieces connect (for milestone 1)
1. Browser loads `http://localhost:5173` (Vite dev server).
2. React's `App.jsx` calls `fetch('/api/health')` on mount.
3. Vite's dev server proxy (configured in `vite.config.js`) forwards any
   request under `/api` to `http://localhost:8080` (the Spring Boot app) —
   this is why the frontend code never needs to know the backend's real
   address.
4. Spring Boot's `HealthController` responds with `{status: "UP", ...}`.
5. Spring Boot itself only finishes starting because it could open a JDBC
   connection to Postgres using the `drivesync_app` credentials from
   `application.yml` — so a green "Backend status: UP" on screen is actually
   proof of three things working together: React, Spring Boot, and Postgres.

### Verification performed
- `./mvnw compile` — backend compiles cleanly.
- `psql -U drivesync_app -d drivesync` — confirmed the dedicated role can
  connect to its own database.
- Started the backend (`./mvnw spring-boot:run`) and confirmed
  `curl http://localhost:8080/api/health` returns `{"status":"UP",...}`.
- Started the frontend (`npm run dev`) and confirmed, via a real browser
  screenshot, that the page renders "Backend status: UP" — i.e. the full
  chain (browser → Vite proxy → Spring Boot → Postgres) works end to end.

---

## Milestone 2 — Watcher + hashing

### What was built
A standalone Java project at [watcher-client/](watcher-client/) (no Spring —
deliberately dependency-light, since I wanted the watcher to stay
lightweight). Key classes:

- [FileHashService.java](watcher-client/src/main/java/com/drivesync/watcher/hashing/FileHashService.java) — computes a SHA-256 hex digest of a file's bytes, streaming through a buffer instead of loading the whole file into memory.
- [FileWatcherService.java](watcher-client/src/main/java/com/drivesync/watcher/watch/FileWatcherService.java) — the heart of this milestone: wraps Java NIO's `WatchService`, debounces bursts of raw OS events, and decides whether a debounced event is a real content change.
- [FileChangeEvent.java](watcher-client/src/main/java/com/drivesync/watcher/model/FileChangeEvent.java) / [ChangeType.java](watcher-client/src/main/java/com/drivesync/watcher/model/ChangeType.java) — the immutable record/enum describing a confirmed change (CREATED/MODIFIED/DELETED).
- [WatcherConfig.java](watcher-client/src/main/java/com/drivesync/watcher/config/WatcherConfig.java) — resolves folder path, device ID, coordinator address, and debounce interval from CLI args → env vars → defaults, so the same jar can run as "device A" or "device B" just by how it's launched.
- [WatcherClientApp.java](watcher-client/src/main/java/com/drivesync/watcher/WatcherClientApp.java) — the runnable entry point; for this milestone it just logs every confirmed change to the console (later milestones swap this listener for one that talks to the coordinator).

### Why these decisions

**Don't trust the raw WatchService event kind — reconcile actual disk state
instead.** This is the single most important design decision in the whole
watcher. Java's `WatchService` (backed by the OS's native notification API)
is notoriously noisy: saving a file in a typical editor can fire several
MODIFY events for one logical save, and the reported event *kind* isn't
always a reliable description of what actually happened. Rather than trying
to interpret every raw event precisely, `FileWatcherService` treats a raw
event only as a hint that *"something might have changed at this path, go
look again"* — and separately, when it does look again, it compares the
file's *current* hash against the *last known* hash for that path to decide
what really happened:
- path now missing, but was known → **DELETED**
- path exists, wasn't known before → **CREATED**
- path exists, hash differs from what was known → **MODIFIED**
- path exists, hash is identical to what was known → **not a change** — emit nothing

That last case is exactly the "don't re-sync unchanged files" functional
requirement, and it's also why hashing matters at all: file size and
timestamps can't tell you this reliably (resaving identical content still
updates the mtime), but a content hash can.

**Debouncing per path.** A burst of raw events for the same file (common
during a save) is collapsed into a single reconciliation check, scheduled
`debounceMillis` (default 800ms) after the *last* event for that path. Each
new event for a path that already has a pending check cancels and
reschedules it, so hashing only happens once the file has been quiet for a
moment — avoiding wasted hashing work and half-written files being hashed
mid-write.

**Content-hash cache lives in memory (`Map<String,String>` of relative path
→ hash), not on disk.** For a single watcher process this is enough — the
map is rebuilt from a full filesystem scan every time the watcher starts
(see below), so nothing is lost on restart. A persistent cache would only
matter for very large folders where re-hashing everything on every startup
becomes slow, which is out of scope for v1.

**Initial scan reports existing files as CREATED, not just future edits.**
The motivating scenario for this whole project is "a laptop died and files
that only existed there were lost" — so files that already exist in the
watched folder when the watcher starts need to be captured too, not only
ones edited afterwards. `performInitialScan()` walks the folder tree once
at startup, hashes everything found, and emits a CREATED event for each
file (this also happens to be what seeds the in-memory hash cache).

**Relative paths use forward slashes.** Paths are relativized to the
watched folder root and normalized to `/` separators (`relativize()` in
`FileWatcherService`) so a path recorded on Windows and a path recorded on
macOS/Linux for "the same" file compare equal — this matters once two
devices' metadata meet at the coordinator server in later milestones.

**No system-wide Maven for this module either** — it reuses the same
Maven Wrapper approach as `server/` (see milestone 1), and produces a single
runnable "fat jar" via the Shade plugin so a watcher instance can be started
with just `java -jar drivesync-watcher.jar --folder ... --device-id ...`
with no classpath setup.

### How the change-detection logic works, step by step
1. **Startup**: walk the watched folder, hash every file found, store
   `relativePath -> hash` in `knownHashes`, and emit a CREATED event for
   each (the baseline). Then register a `WatchService` watch on the root
   folder and every subdirectory (`WatchService` is not recursive by
   itself — each directory has to be registered individually).
2. **An OS-level event arrives** (create/modify/delete at some path). The
   watch loop thread does *not* act on it directly — it calls
   `scheduleReconcile(path)`, which cancels any already-pending check for
   that same path and schedules a new one `debounceMillis` in the future.
3. **After the debounce window elapses with no further events for that
   path**, `reconcile()` runs on a background scheduled-executor thread:
   - If the file no longer exists on disk but was in `knownHashes` →
     remove it from the map, emit DELETED.
   - If the file exists, hash it, and compare to `knownHashes.get(path)`:
     - no previous hash → CREATED, store the new hash
     - hash differs → MODIFIED, store the new hash
     - hash is the same → do nothing (this is the "skip unchanged files"
       behavior)
4. A new subdirectory appearing gets its own watch registered immediately
   (`tryRegisterNewDirectory`), and any files already inside it (e.g. a
   folder dragged in wholesale) get scheduled for reconciliation too, so
   moving a whole folder in is treated the same as creating each file
   individually.
5. If the OS's event buffer overflows (`OVERFLOW` event — meaning some
   events were dropped and we genuinely don't know what we missed), the
   whole subtree under that watch is rescanned rather than trying to guess.

### Verification performed
- **Automated tests** ([FileWatcherServiceTest.java](watcher-client/src/test/java/com/drivesync/watcher/FileWatcherServiceTest.java)), run against a real temp directory and a real `WatchService` (not mocked, since the whole point of this class is its interaction with the OS watch API):
  - a new file is reported as CREATED with a hash
  - a genuine content change is reported as MODIFIED
  - **rewriting a file with byte-for-byte identical content produces NO event** — the key proof that hash-based detection (not mtime/size) is what drives sync decisions
  - a deleted file is reported as DELETED with a null hash
  - All 4 tests pass (`./mvnw test`).
- **Live manual test**: built the shaded jar, ran it against `sync-folder-a`
  with a pre-existing file already in the folder, and watched the console
  log in real time while creating, identically-rewriting, genuinely
  modifying, and deleting files from a separate shell. Observed exactly the
  expected sequence: CREATED (pre-existing baseline file) → CREATED (new
  file) → CREATED (a test file) → *(silent — identical rewrite correctly
  produced no log line)* → MODIFIED (same file, genuinely changed) →
  DELETED (the new file, removed).

---

## Milestone 3 — Coordinator server core (REST + Postgres schema)

### What was built
Three JPA entities/tables, three REST endpoints, and the service layer
connecting them:

- [Device.java](server/src/main/java/com/drivesync/server/model/Device.java) / `devices` table — one row per watcher instance that has ever registered. Tracks `firstSeenAt`, `lastSeenAt`, and `online` (the last of which milestone 4's WebSocket layer will actually drive - REST activity alone doesn't mean "currently connected").
- [FileChangeRecord.java](server/src/main/java/com/drivesync/server/model/FileChangeRecord.java) / `file_change_records` table — an **append-only log**: "device X reported path Y changed, this way, at this time." Never updated or deleted. This is both the activity feed's data source (milestone 6) and the mechanism catch-up sync uses.
- [FileState.java](server/src/main/java/com/drivesync/server/model/FileState.java) / `file_states` table — one row per file path holding the coordinator's current understanding of that file (latest hash, who changed it last, which log entry produced it). Mutable, and always derived from the log above.
- [SyncCoordinatorController.java](server/src/main/java/com/drivesync/server/controller/SyncCoordinatorController.java) — `POST /api/sync/changes` (submit a change) and `GET /api/sync/changes?since=&excludeDevice=` (catch-up fetch).
- [DeviceController.java](server/src/main/java/com/drivesync/server/controller/DeviceController.java) — `POST /api/devices/register` and `GET /api/devices`.
- [SyncCoordinatorService.java](server/src/main/java/com/drivesync/server/service/SyncCoordinatorService.java) — the actual decision logic, kept out of the controller so it's independently testable.

### Why these decisions

**Two tables instead of one (log + current-state), rather than just querying
"the latest row per path" out of one table.** Postgres *could* answer
"what's the latest state of path X" by querying the log with
`ORDER BY id DESC LIMIT 1`, but that gets more expensive as the log grows,
and more importantly: milestone 5's conflict detection needs to ask "what do
we currently believe about this path" very frequently (on every single
change submission), so it deserves to be an O(1) lookup by primary key
rather than a scan/sort of the whole history for that path. Keeping
`FileState` as an explicit, always-up-to-date table also makes the eventual
dashboard's "current status of all files" view trivial to implement -
`SELECT * FROM file_states` - instead of a windowed/grouped query over the log.

**The log's auto-increment `id` doubles as the catch-up cursor/version
number**, rather than inventing a separate versioning scheme. Postgres
guarantees these ids are assigned in increasing order as rows commit, so
"give me everything after id N" is both correct and just an indexed range
scan (`findByIdGreaterThanOrderByIdAsc`). A reconnecting device just needs
to remember the highest id it last saw.

**Server-side dedup, in addition to the watcher's own client-side dedup.**
The watcher already skips submitting a change if its own hash cache says
nothing changed (milestone 2) - so why would the server ever receive a
"duplicate"? Because the two are independent processes with independent
state: the server might have missed an earlier submission (a crash, a
network blip), or a watcher's local cache could be stale after a restart.
`SyncCoordinatorService.submitChange()` compares the incoming hash+type
against `FileState` and returns `duplicate: true` without writing a new log
row when they match - the log only grows for changes that actually change
something, from the server's point of view too.

**No conflict detection yet.** A new submission for a path currently just
overwrites `FileState` for that path (last-write-wins) regardless of who
changed it last. This is intentionally incomplete - milestone 5 adds the
comparison logic that turns "two devices both changed the same path" into a
detected, surfaced conflict instead of silent data loss. Building it now
would mean designing it before milestone 4 (WebSocket) exists, when the
watcher client doesn't even have a way to know what the server's current
state is yet - the two features are more coherent built together.

**No shared Java module between server and watcher-client.** Both sides
have their own copy of a `ChangeType` enum and independently define their
JSON shapes. They're separate deployables that only need to agree on the
wire format (JSON over HTTP), the same as any client/server pair - a shared
library module would just couple two independent build/release lifecycles
for the sake of not retyping one small enum.

### How change submission works, step by step
1. Watcher POSTs `{deviceId, relativePath, changeType, contentHash,
   sizeBytes, clientDetectedAt}` to `/api/sync/changes`.
2. The device is touched (upserted) in `devices` - this endpoint alone is
   enough to keep `lastSeenAt` honest even before WebSocket exists.
3. The server loads `FileState` for that path, if any exists yet.
4. If it exists AND its `currentHash`+`lastChangeType` exactly match what
   was just submitted → this is a duplicate: no new `FileChangeRecord` is
   written, and the response points back at the record that already
   describes this state, with `duplicate: true`.
5. Otherwise: a new `FileChangeRecord` is inserted (this is what advances
   the "version"/cursor, since its id is auto-generated), and `FileState`
   for that path is updated to point at it.
6. Both writes happen in one `@Transactional` method, so a reader can never
   see a `FileChangeRecord` whose corresponding `FileState` update didn't
   also happen (or vice versa).

### How catch-up fetch works
A reconnecting device calls `GET /api/sync/changes?since=<lastKnownId>&excludeDevice=<itself>`.
The server runs an indexed range query for every `FileChangeRecord` with
`id > since`, excludes rows where `deviceId` equals the caller's own id
(no point telling a device about changes it made itself), and returns them
in ascending id order - i.e. in the order they actually happened.

### Verification performed
- Inspected the Hibernate-generated schema directly in `psql` (`\dt`, `\d`)
  and confirmed all three tables, their columns, and the `CHECK` constraints
  Hibernate generated for the enum columns.
- **Manual end-to-end verification with curl** against the running server:
  registered two devices, listed them back; submitted a change (got
  `duplicate: false`); resubmitted the identical change (got back the
  *same* record id with `duplicate: true` - confirmed no new row was
  written); submitted a genuinely different change to the same path (got a
  new id, `duplicate: false`); fetched catch-up as `laptop-b` and saw both
  of `laptop-a`'s changes; fetched catch-up excluding `laptop-a` itself and
  correctly got an empty list; fetched with `since=1` and correctly got
  only the second change; confirmed a malformed request (missing required
  field) is rejected with `HTTP 400` by bean validation.
- **Automated tests** ([SyncCoordinatorServiceTest.java](server/src/test/java/com/drivesync/server/SyncCoordinatorServiceTest.java)), run against the real local Postgres but wrapped in `@Transactional` so every test's writes roll back automatically: first submission isn't a duplicate; resubmitting identical content/type IS a duplicate and points at the original id; a genuine change after a duplicate gets a new id; catch-up excludes the requester's own changes; catch-up `since` cursor only returns later changes. All 5 pass. Confirmed via `psql` afterwards that no test data leaked into the dev database.

---

## Milestone 4 — Real-time sync (WebSocket) + content transfer

### A design gap in my original plan, and how it was resolved
My plan had Postgres storing only metadata, never raw file content, and
sync being client-server (not peer-to-peer). Taken together, those two
constraints leave a real question unanswered: if the database never holds
file bytes and devices never talk to each other directly, how does content
actually get from device A to device B? I resolved this before building
this milestone: the coordinator server keeps a
**content-addressed blob store on its own local disk** (`server/data/blobs/`,
outside Postgres entirely, files named by their SHA-256 hash). Metadata
(what changed, its hash) flows through Postgres and WebSocket/REST as
before; the bytes themselves flow through this separate blob store via two
new endpoints.

### What was built

**Server side:**
- [BlobStorageService.java](server/src/main/java/com/drivesync/server/service/BlobStorageService.java) / [BlobController.java](server/src/main/java/com/drivesync/server/controller/BlobController.java) — `PUT/GET/HEAD /api/sync/blobs/{hash}`. Content-addressed: the server recomputes SHA-256 of any uploaded bytes and REJECTS a claimed hash that doesn't match (never trusts a caller's claim about its own data), and writes go to a temp file then an atomic move into place so a concurrent reader can never see a half-written blob.
- [WebSocketConfig.java](server/src/main/java/com/drivesync/server/config/WebSocketConfig.java) — enables STOMP over a raw WebSocket endpoint at `/ws`, with a simple in-memory topic broker (`/topic/*`). No `@MessageMapping` handlers exist here on purpose: clients only ever *subscribe*, they never send data over this channel - all writes still go through REST, keeping "what actually happened" logic in exactly one place.
- [DeviceIdHandshakeInterceptor.java](server/src/main/java/com/drivesync/server/ws/DeviceIdHandshakeInterceptor.java) — reads `?deviceId=...` off the WebSocket handshake URL and stashes it as a session attribute, so later STOMP lifecycle events know which device connected.
- [PresenceEventListener.java](server/src/main/java/com/drivesync/server/ws/PresenceEventListener.java) — listens for STOMP connect/disconnect and flips `Device.online` accordingly, broadcasting the new status to `/topic/devices`.
- [ChangeBroadcastService.java](server/src/main/java/com/drivesync/server/ws/ChangeBroadcastService.java) — thin wrapper around `SimpMessagingTemplate`; `SyncCoordinatorService` calls it, via an `afterCommit` transaction hook, once a real (non-duplicate) change is durably saved.

**Client side (watcher-client), all in the new `sync` package:**
- [SyncApiClient.java](watcher-client/src/main/java/com/drivesync/watcher/sync/SyncApiClient.java) — REST calls (register, submit, catch-up, blob upload/download/exists) built on the JDK's own `java.net.http.HttpClient` - no REST client library added.
- [LocalChangeUploader.java](watcher-client/src/main/java/com/drivesync/watcher/sync/LocalChangeUploader.java) — the `FileChangeListener` that replaces milestone 2's console logger: uploads content, then submits metadata.
- [RemoteChangeApplier.java](watcher-client/src/main/java/com/drivesync/watcher/sync/RemoteChangeApplier.java) — applies a change that happened on the OTHER device (from either catch-up or live push) to the local folder.
- [LiveSyncSubscriber.java](watcher-client/src/main/java/com/drivesync/watcher/sync/LiveSyncSubscriber.java) — a **hand-rolled minimal STOMP-over-WebSocket client**, built directly on `java.net.http.WebSocket` rather than pulling in Spring's STOMP client library, to keep the watcher dependency-light and to make the protocol's actual text framing visible (see the class's doc comment for what a STOMP frame looks like on the wire).
- [FileWatcherService.java](watcher-client/src/main/java/com/drivesync/watcher/watch/FileWatcherService.java) gained `applyRemoteWrite()` / `applyRemoteDelete()`.

### Why these decisions

**REST for writes, WebSocket only for one-way notification.** A client
never sends data over STOMP in this project - it only subscribes. Every
actual state change (a submitted file change, a device registering) goes
through REST, gets validated and persisted exactly once, and WebSocket is
purely a broadcast side-channel layered on top telling already-connected
clients "something happened, go look." This avoids having two different
code paths that can both mutate state.

**A hand-rolled STOMP client instead of Spring's `WebSocketStompClient`.**
Using Spring's client would have pulled `spring-messaging`/`spring-websocket`
into the watcher, working against the "lightweight watcher" goal from the
spec, and would have hidden exactly the mechanism this project exists to
teach. STOMP's frame format is simple enough (see `LiveSyncSubscriber`'s doc
comment) that implementing just the four frame types actually needed
(CONNECT/CONNECTED/SUBSCRIBE/MESSAGE) was a worthwhile, bounded trade.

**Echo suppression falls out of the existing hash-based design for free.**
The server broadcasts every change to *every* subscriber, including the
device that made it - there's no "exclude the sender" in a simple STOMP
topic broker. Two different mechanisms handle the two different echo risks:
- `RemoteChangeApplier` compares `change.deviceId()` against its own device
  ID and ignores its own broadcasts outright.
- More subtly: when a device applies a genuinely REMOTE change by writing
  to its local folder, the OS's file-watch API still fires a normal local
  "this file changed" event for that write - there's no way to write a file
  "silently" at the OS level. `applyRemoteWrite`/`applyRemoteDelete` handle
  this not with a special suppression flag, but by updating `knownHashes`
  (the same map milestone 2's `reconcile()` already checks) to match the
  new reality. When the debounced reconcile eventually runs for that path,
  it sees the hash already matches what's "known" and silently does
  nothing - the exact same mechanism that skips a no-op local resave also
  happens to prevent an infinite remote-apply-detects-as-local-change loop.

### Two real bugs found by actually running two watchers against each other

Writing the code and reasoning about it was not enough to catch either of
these - both only showed up when two real watcher processes were run
against a real server and files were genuinely created/edited/deleted
between them.

1. **A race condition between announcing a change and having its content
   available.** The original `LocalChangeUploader` submitted change metadata
   FIRST, then uploaded the blob. But submitting metadata is exactly what
   triggers the server to broadcast the change to other devices - and that
   broadcast happens synchronously, inside the same request, before the
   HTTP response even returns to the submitting device. The result: device
   B could receive "path X changed, hash Y" and try to download blob Y
   before device A had gotten around to uploading it, getting a 404. Fixed
   by reordering `LocalChangeUploader` to upload content BEFORE submitting
   metadata - by the time any other device can possibly hear about a
   change, the content it refers to is guaranteed to already exist. Caught
   by watching `watcher-b.log` print a real `WARN ... failed with HTTP 404`
   during a live two-watcher test.

2. **A broadcast could theoretically fire before its transaction
   committed.** Even after the reordering above, the initial version of
   `SyncCoordinatorService.submitChange()` called
   `broadcastService.broadcastChange()` inline, midway through an
   `@Transactional` method. Since Spring's transactional proxy only commits
   once the method returns, this meant a broadcast could go out to other
   devices before the corresponding database row was durably committed -
   if something later in that same transaction had failed and rolled back,
   devices would have been notified about a change that never actually
   happened. Fixed by registering the broadcast as a
   `TransactionSynchronization.afterCommit()` callback instead, guaranteeing
   the row is durably saved before anyone is told about it. (This one
   wasn't caught by testing - there was nothing after the broadcast call
   that could actually fail in the current code - but it's a correctness
   bug waiting to happen the moment more logic is added to that method, so
   it was fixed on inspection rather than left as a landmine.)

3. **Presence tracking silently did nothing.** `PresenceEventListener`
   originally listened for `SessionConnectedEvent` to mark a device online.
   Devices reliably went OFFLINE on disconnect (proving the mechanism for
   reading the device ID out of session attributes was correct) but NEVER
   went ONLINE - `GET /api/devices` showed `online: false` for devices that
   were, at that exact moment, connected and actively syncing. Since the
   disconnect side worked with the identical attribute-reading code,  the
   bug had to be the event type itself: switching to `SessionConnectEvent`
   (fired when the CONNECT frame arrives, rather than when the CONNECTED
   reply is sent back) fixed it immediately. Caught by checking
   `GET /api/devices` mid-test and noticing both devices showed offline
   despite both watcher processes clearly running and syncing successfully.

### Verification performed
- Ran two real watcher processes (`laptop-a` against `sync-folder-a`,
  `laptop-b` against `sync-folder-b`) against the real backend and real
  Postgres - not simulated.
- **Create**: created a file in `sync-folder-a`; confirmed via log output
  and file content comparison that it appeared in `sync-folder-b` within
  about 30ms of the debounce window elapsing, with byte-identical content.
- **Modify**: edited the same file from `sync-folder-b`; confirmed the
  change propagated back to `sync-folder-a`.
- **Delete**: deleted the file from `sync-folder-a`; confirmed it was
  removed from `sync-folder-b`.
- Confirmed neither watcher ever re-reported a remotely-applied change as
  a new local one (no echo loop) by inspecting both watchers' logs for the
  full sequence.
- **Presence**: confirmed `GET /api/devices` shows `online: true` while a
  watcher is connected and flips to `false` within moments of stopping it.
- Re-ran both the server's and the watcher's full automated test suites
  after all changes - all 9 tests (5 server + 4 watcher) still pass.

---

## Milestone 5 — Conflict detection & resolution

### Decided before building
I settled two rules before writing any code: (1) the default resolution
behavior is keep-both-renamed - never a silent overwrite, both versions
always preserved, the dashboard can override afterward; and
(2) when both versions are kept, the edit with the LATER `clientDetectedAt`
(the device's own clock at the moment it locally detected the change) keeps
the original filename, and the earlier one becomes the conflicted copy.

### What was built

**Detection - optimistic concurrency via a "base version":**
Every change submission now carries `baseChangeId`: the change id the
submitting device believes is CURRENTLY authoritative for that path - the
last one it either made itself or applied from elsewhere, tracked
client-side by the new [PathVersionTracker.java](watcher-client/src/main/java/com/drivesync/watcher/sync/PathVersionTracker.java).
This is the same idea as a database's optimistic-locking version column, or
how Git detects a non-fast-forward push. [ConflictResolver.isConflict()](server/src/main/java/com/drivesync/server/service/ConflictResolver.java)
flags a genuine conflict only when BOTH are true: the submitted
`baseChangeId` doesn't match the path's current `FileState.lastChangeId`
(the device's knowledge is stale), AND the submitted content hash is
actually different from what's on the server (a stale-but-identical
submission isn't a real conflict - nothing to reconcile).

**Resolution - keep both, newer wins the name:**
`ConflictResolver.handleConflict()` figures out the winner (later
`clientDetectedAt`), persists the LOSING content as an ordinary new
`FileChangeRecord`+`FileState` under a new path -
`"name (conflicted copy - deviceId).ext"` (built by `buildConflictedCopyPath()`)
- and records a `Conflict` row for the dashboard. The losing content
syncs to every device through the exact same mechanism as any other file
(catch-up, live push) - no special-case sync logic needed downstream. A new
[ConflictController.java](server/src/main/java/com/drivesync/server/controller/ConflictController.java)
exposes `GET /api/conflicts` and `POST /api/conflicts/{id}/resolve` for the
dashboard's manual-override path (pick which device's version keeps the
original name, regardless of the automatic choice).

**Client side:** [LocalChangeUploader.java](watcher-client/src/main/java/com/drivesync/watcher/sync/LocalChangeUploader.java)
now inspects its own submission's response: if `conflict && conflictedCopyPath != null`,
it means THIS device's own edit lost - it preserves its own content under
the conflicted-copy name and restores the winning content at the original
path, both via `FileWatcherService.applyRemoteWrite()` (never a plain file
move) so neither operation is mistaken for a new local edit and re-uploaded.

### Why these decisions
- **`baseChangeId` instead of comparing hashes alone**: hash comparison can
  tell you content differs, but not WHY - was this device building on stale
  knowledge (a real conflict), or did it just make an unrelated sequential
  edit after correctly catching up? Only a version/base check answers that.
- **The losing content becomes an ordinary new file, not a special
  "conflict object"**: this is what let the WHOLE distribution mechanism
  (catch-up REST fetch, live WebSocket push, blob download) be reused
  as-is, with zero new client-side sync logic beyond recognizing "this
  submission response says I lost."
- **Automatic resolution happens immediately, not "pending user review"**:
  "no silent overwrites" is satisfied the instant a conflict is detected,
  independent of whether or when anyone looks at a dashboard - both files
  already exist as real, synced content before any human is involved. The
  dashboard's manual resolve is an override of an already-safe default, not
  a gate that has to be cleared first.

### Three real bugs found by live-testing an actual conflict
Automated tests (written against the service layer directly) caught the
core detection/winner logic correctly on the first try. All three of these
bugs were specifically about what happens to files on DISK across two real,
separately-running watcher processes - exactly the kind of thing unit tests
against a service layer can't see.

1. **The "passively superseded" device's echo filter wrongly ate its own
   backup-file notification.** `RemoteChangeApplier` originally filtered
   broadcasts by `ownDeviceId.equals(change.deviceId())` ("I made this
   change, so I already have it - skip"). That assumption breaks for
   exactly one case: when device A's PREVIOUSLY-ACCEPTED, now-retroactively-superseded
   content gets relocated to a conflicted-copy path by the SERVER (not by
   A actively submitting anything new) - the synthetic record is
   attributed to A's own deviceId (since it IS A's content), so A's own
   deviceId-based filter silently treated the server's "here's your backup
   file" notification as its own echo and never created the file. The
   symptom in testing: one device ended up with only the winning file (no
   backup created), while the other correctly had both - the two devices'
   folders silently diverged. **Fixed** by replacing deviceId-based echo
   suppression with CONTENT-based suppression:
   `FileWatcherService.alreadyHasContent(path, hash)` /`alreadyAbsent(path)`
   ask "do I already have exactly this content at exactly this path?"
   instead of "did I make this?" - which has no blind spot, because it's
   really asking the only question that actually matters. This also made
   the code simpler: `RemoteChangeApplier` no longer needs to know its own
   device id at all.
2. **The original-path broadcast, when the OTHER device's content won,
   never reached the just-superseded device correctly** - a direct
   consequence of bug #1, fixed by the same change: once content-based
   filtering was in place, the corrective "the original path now has this
   other content" broadcast was applied correctly by every device that
   didn't already have that exact content, regardless of whose deviceId
   was attached.
3. **Manual conflict resolution crashed with HTTP 500**
   (`IllegalStateException: Transaction synchronization is not active`).
   `ConflictResolver.resolveManually()` registered an `afterCommit` hook
   the same way `handleConflict()` does, but unlike `handleConflict()`
   (always called from inside `SyncCoordinatorService.submitChange()`,
   which is `@Transactional`), `resolveManually()` is invoked directly from
   `ConflictController` with no transaction active - there was nothing for
   "after commit" to mean. **Fixed** by adding `@Transactional` to both
   `handleConflict()` and `resolveManually()` directly, so neither depends
   on a caller happening to already be inside one.

### Verification performed
- **Automated** ([SyncCoordinatorServiceTest.java](server/src/test/java/com/drivesync/server/SyncCoordinatorServiceTest.java)):
  added a test simulating two devices editing the same baseline
  independently (one submission with an up-to-date base, one with a stale
  base and different content) - confirms the conflict is detected, the
  later edit wins the original name, the earlier edit's content is
  preserved verbatim under a path containing "conflicted copy" and the
  losing device's name, and the losing device's response correctly carries
  the winning content's hash to restore. All 6 server tests pass.
- **Live, with a real race**: two real watcher processes, both already in
  sync on a shared file, edited with DIFFERENT content within milliseconds
  of each other (faster than the ~500ms debounce + network round trip) -
  a direct simulation of "two devices independently changed the same file
  while apart," without needing to actually stop/restart a process (which
  would lose the in-memory `knownHashes`/`PathVersionTracker` state - see
  the limitation noted below). Confirmed via `GET /api/conflicts` and by
  reading the actual files on both disks: both devices converged to
  IDENTICAL final state - the same content at the original filename, and
  the same losing content byte-for-byte preserved under the same
  conflicted-copy filename, on BOTH machines. Then tested the manual
  override (`POST /api/conflicts/{id}/resolve`) with the non-default
  device and confirmed the original path's content flipped, live, on both
  devices, while the conflicted-copy backup file remained untouched.

### A known limitation, honestly documented
`PathVersionTracker` and `FileWatcherService`'s `knownHashes` are both
in-memory only, rebuilt from scratch on every restart (see milestone 2's
notes on `knownHashes`). This means a restarted watcher has no way to tell
"this local file is unchanged since I last synced" apart from "this local
file was independently edited while I was offline" - both simply look like
"a file exists with some content" after a restart, since there's no
persisted memory of what the PREVIOUS state was. A production-grade version
of this project would persist a small local index (e.g. a SQLite file) of
last-synced hash+version per path specifically so this distinction survives
a restart. For v1, this is why the conflict test above used a live race
between two continuously-running processes rather than a stop/restart -
which is also, worth noting, a perfectly realistic way for this exact
scenario to happen in practice (two people editing the same shared file at
the same moment), just not the ONLY way conflicts can arise in a more
complete implementation.

---

## Milestone 6 — React dashboard

### What was built
A live dashboard at [web/src/App.jsx](web/src/App.jsx), composed of three
panels, all wired to the real backend with zero mock data:

- [DeviceStatusPanel.jsx](web/src/components/DeviceStatusPanel.jsx) — every
  registered device with a live online/offline dot.
- [ActivityFeed.jsx](web/src/components/ActivityFeed.jsx) — a
  newest-first, live-updating feed of every file change, across every device.
- [ConflictsPanel.jsx](web/src/components/ConflictsPanel.jsx) — active
  conflicts with a manual "keep this version instead" override per side,
  and a collapsible history of resolved ones.

Backed by a new [liveSyncClient.js](web/src/ws/liveSyncClient.js), which
subscribes to the same `/topic/changes`, `/topic/devices`, and
`/topic/conflicts` STOMP topics the Java watcher clients do - using
`@stomp/stompjs` (a real dependency here, unlike the watcher client: the
"keep it lightweight" goal was specifically about the Java
watcher, not the browser dashboard, so there's no reason to hand-roll STOMP
framing a second time in JavaScript). A small new server endpoint,
`GET /api/sync/activity` (newest-first, distinct from the ascending
`since=`-cursor endpoint catch-up uses), backs the initial feed load.

### Why these decisions
- **REST for the initial snapshot, WebSocket for everything after.** On
  mount, the dashboard fetches devices/activity/conflicts once over REST -
  this is its own "catch-up," conceptually identical to what a watcher does
  on startup. From then on, the three STOMP subscriptions keep it current
  with zero polling. This mirrors the exact same REST-for-state,
  WebSocket-for-notification split used throughout the whole project (see
  milestone 4).
- **The dashboard's WebSocket connection carries no `?deviceId=`.** A
  watcher's connection does, so the server's presence tracking
  (`PresenceEventListener`) can mark it online/offline. The dashboard isn't
  a sync participant - it doesn't have a folder, doesn't submit changes -
  so deliberately omitting the query param keeps it from ever appearing as
  a fake "device" in its own device list.
- **Resolving a conflict optimistically updates local state AND relies on
  the WS broadcast.** `handleResolve` in `App.jsx` applies the server's
  response to `conflicts` state immediately (so the UI feels instant),
  while the exact same update also arrives moments later via
  `/topic/conflicts` - applying it twice is a harmless no-op merge (same
  `id`, same fields), and this means the resolution feels instant for the
  person who clicked it while still being correctly live for anyone else
  who might have the dashboard open.
- **Activity feed dedup by id.** Between the initial REST fetch and the WS
  subscription actually connecting, there's a small window where a change
  could arrive via both paths. `setActivity` checks for an existing `id`
  before prepending, so a change is never shown twice.

### Verification performed
Ran the full system live - real backend, real Postgres, two real watcher
processes, and the dashboard open in an actual browser (not a mocked
component test) - and confirmed, entirely without a page refresh:
- Starting both watchers and creating files on each showed both devices go
  "online" and both files appear in the activity feed within the same
  second they were created.
- Triggering a genuine conflict (two watchers editing the same file within
  milliseconds via truly-parallel writes) made the conflict card appear
  live in the Conflicts panel, correctly showing which device currently
  "keeps the name" and which was "saved as backup," with the right
  filenames and truncated hashes.
- Clicking "Keep this version instead" on the losing side correctly:
  flipped the original file's content on BOTH real devices' disks (verified
  by reading the actual files), moved the conflict into the collapsed
  "resolved" history with the right device name and timestamp, and added
  the corrective change to the activity feed - all live, no refresh.
- Stopping one watcher process flipped its dashboard status from a green
  "online" dot to a gray "last seen just now" within about a second.

---

## Milestone 7 — End-to-end local test

This milestone added no new code - it's a single, continuous, formal
verification pass tying every previous milestone together, run against a
completely fresh database and empty folders, with the dashboard open the
entire time. Sequence:

1. **Started only `laptop-a`** and created two files (`README.md`,
   `todo.txt`) before `laptop-b` ever existed.
2. **Started `laptop-b` for the first time.** Confirmed it caught up on
   BOTH pre-existing files via its startup catch-up fetch, byte-for-byte
   identical to `laptop-a`'s copies (`diff` reported no differences) -
   proving catch-up correctly handles a device's very first connection,
   not just reconnection after being known.
3. **Live sync**: created a file on `laptop-a` with both watchers running;
   confirmed it appeared on `laptop-b` within the debounce window.
4. **Deliberate offline/reconnect test**: stopped the `laptop-b` process
   entirely (not just its WebSocket - the whole watcher). While it was down,
   made THREE different kinds of changes on `laptop-a`: modified an
   existing file, created a brand new file, and deleted a file. Restarted
   `laptop-b` and confirmed its catch-up fetch (which runs before the
   watcher's own local scan starts - see milestone 4) correctly applied
   all three kinds of missed changes: the modified file had the new
   content, the new file existed, and the deleted file was gone. All three
   change types working correctly across a real process restart is the
   key thing this test needed to prove, since catch-up's REST-based
   "everything since the last id you saw" design (milestone 3) has to
   treat CREATED/MODIFIED/DELETED uniformly for this to work.
5. **Deliberate conflict test**: with both devices online, wrote genuinely
   different content to the same shared file from both devices via truly
   parallel background writes (to close the race window as tightly as
   possible - see milestone 5's notes on why a live race is used instead
   of a stop/restart for this specific test). Confirmed via both the
   `GET /api/conflicts` API and by reading the actual files from both
   devices' disks that: a conflict was detected, both versions were
   preserved, and both devices converged to byte-identical final state.
6. **Dashboard visibility**: throughout all of the above, the dashboard
   (open in an actual browser this whole time) correctly showed both
   devices online, the running activity feed including the DELETED entry
   from step 4, and the conflict card from step 5 - confirmed with a
   screenshot at the end of the run showing the complete, accurate history.
7. Re-ran the full automated test suite for both the server (6 tests) and
   the watcher client (4 tests) one final time - all 10 pass.

### What this run confirms, and what it doesn't
This proves the system works correctly for its actual intended use case:
two personal devices, normal everyday edits, occasional time apart,
occasional simultaneous edits to the same file. It does NOT prove behavior
under load (many files, large files, high-frequency edits), under real
network conditions (only localhost was ever tested - no real latency,
packet loss, or two machines on different networks), or across an actual
watcher restart combined with a genuine conflict (the known in-memory-state
limitation from milestone 5 means that specific combination isn't
currently handled) - all reasonable follow-ups, explicitly out of v1
scope, and not attempted here.

**All 7 build milestones are now complete.** Phase 2 - cloud deployment,
a real two-device test over the internet, and support for more than two
devices - is not started.
