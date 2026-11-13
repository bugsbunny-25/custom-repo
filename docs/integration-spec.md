# Integration spec for the `homelab` repo

Everything the host side needs in order to run this image after the
slim-down. Written to be applied in the `homelab` repo; nothing here changes
this repository.

**Nothing in this spec is required on day one.** The image's defaults keep the
current single-container, all-in-one behaviour, so an unattended
`podman auto-update` pull is safe before any of this is applied. Sections 1–4
describe that state. Section 5 (the split) and section 7 (the storage move)
are opt-in and can be done later, independently of each other.

---

## 0. Read this first: a live secret exposure

The `nginx.conf` this image shipped until now set `root /srv/fdroid` with a
catch-all `location /` and `autoindex on`. That served **every file in the
repo data directory**, including:

| URL | File |
|---|---|
| `/config.yml` | contains `keystorepass:` and `keypass:` in plaintext |
| `/keystore.p12` | the repo signing key (alias `8d794fa7a0a2`) |
| `/patched-keystore.jks` | the patched-apps signing key |
| `/patched-tv-keystore.jks` | the patched-TV signing key |

Verified against the old config in a container: `GET /config.yml` returns
**200** with the passwords in the body, and `GET /keystore.p12` returns
**200**. Because SWAG proxies `fdroid.2160pbrewing.uk` to this container,
those URLs were reachable from the internet.

Anyone holding that keystore and password can sign APKs that every subscribed
F-Droid client accepts as genuine.

The new `nginx.conf` in this image serves only the documented paths and
returns 404 for everything else, which closes it. Before that image is
deployed, consider doing all of the following:

1. **Block it at the proxy now**, independently of this image, e.g. in SWAG:
   `location ~ ^/(config\.yml|.*\.p12|.*\.jks|.*\.keystore)$ { return 404; }`
2. **Check the access logs** for whether anyone fetched those paths:
   `grep -E '/(config\.yml|keystore\.p12|patched-keystore\.jks|patched-tv-keystore\.jks)' /var/mnt/config/apps/custom-fdroid/logs/nginx/access.log*`
   Absence of hits is good evidence; presence means treat the keys as
   compromised.
3. **Decide about key rotation.** This is yours to weigh, not mine: rotating
   the repo key forces every user to remove and re-add the repo, which the
   brief explicitly rules out under normal circumstances. If the logs are
   clean, keeping the key is defensible. If they are not, the choice is
   between forcing a re-add and leaving a key in the wild that can sign
   anything.

---

## 1. Image and tags

- Registry: `ghcr.io/bugsbunny-25/custom-repo`
- `:main` — moving tag, built on every push to `main` (unchanged).
- `:vX.Y.Z`, `:vX.Y`, `:vX` — **new**, immutable version tags, published when
  a `vX.Y.Z` git tag is pushed. `:vX.Y.Z` never moves, so it is the one to
  pin for reviewable, rollback-able updates.
- `:sha-<short>` — **new**, every build, for pinning an exact commit.

Both architectures (`linux/amd64`, `linux/arm64`) are published under one
manifest list, as before.

To cut a version tag:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

**Recommendation:** switch the quadlet from `:main` to a `:vX.Y.Z` tag and
drop `AutoUpdate=registry`, so updates become a deliberate edit. If you keep
`AutoUpdate=registry`, keep it on `:main`; `podman auto-update` on an
immutable tag has nothing to do.

---

## 2. Containers

### 2.1 `fdroid-repo` (always on) — this is the only required container

| | |
|---|---|
| Command | `server` (the image's default `CMD`, so it can be omitted) |
| Internal port | **80** (unchanged) — nginx |
| Health endpoint | `/health` (nginx, unchanged) **and** `/api/health` (new, answered by the JVM) |
| Container `HEALTHCHECK` | built in; probes both of the above |

The container's own `HEALTHCHECK` is new. It matters for `podman
auto-update`: `/health` alone is a static `return 200` from nginx and keeps
answering while the JVM behind `/admin` and `/api/` is wedged, so an image
whose app is broken would previously have looked healthy and never been
rolled back.

> **If you added a `HealthCmd=` to the quadlet as a workaround, you can drop
> it.** The first published image carried its health check in the registry but
> Podman ignored it, because Buildx pushed OCI media types and the OCI image
> config spec has no `Healthcheck` field
> ([containers/podman#18904](https://github.com/containers/podman/issues/18904)).
> The image is now published with Docker media types, and CI fails the publish
> if the manifests are not Docker-typed or the health check is missing — so
> `podman inspect fdroid-repo --format '{{json .Config.Healthcheck}}'` should
> show it rather than `null`. Keeping the quadlet's own `HealthCmd` is
> harmless if you prefer it; it just overrides the image's.

**PID 1 is now the JVM, not supervisord.** supervisord was removed. If the
JVM dies the container exits and podman restarts or rolls back; if nginx dies
the HEALTHCHECK fails. Consequence for you: **the app's log is no longer at
`/var/log/supervisor/fdroid-server.log`** — it goes to the container's stdout,
so use `podman logs fdroid-repo` or journald. nginx still logs to
`/var/log/nginx/`.

### 2.2 `fdroid-builder` (one-shot) — only needed for the split in section 5

| | |
|---|---|
| Command | `builder run [--full\|--requests-only]` |
| Port | none, serves nothing |
| Health endpoint | none; it exits, so read its exit code and `.status/builder.json` |

Arguments:

- `builder run --full` — do everything (GitHub releases, patch sources, both
  patch channels, index updates, archive prune, cleanup). For the nightly
  timer.
- `builder run --requests-only` — only act on queued request files; exit 0
  immediately if there are none. For the `.path` unit.
- `builder run` — act on queued requests, or do a full run if none are
  queued.

Exit codes: `0` finished / nothing to do / another builder held the lock;
`1` at least one step failed; `2` bad arguments.

It takes a lock (`.status/builder.lock`, an OS advisory lock) for the whole
run, so a timer firing while a `.path`-triggered run is in progress is safe —
the second one exits 0 without doing anything. Every step is idempotent, so a
killed run can simply be triggered again.

---

## 3. Volumes

| Container path | What is in it | Backup? |
|---|---|---|
| `/srv/fdroid` | repo data: `repo/`, `archive/`, `patched/`, `patched-tv/`, `metadata/`, `config.yml`, `tmp/`, `.requests/`, `.status/` | **mixed** — see below |
| `/app/db` | `app.db`, SQLite: settings, GitHub repos, patch library and targets, checked-release history | **yes** (small) |
| `/keys` | **new, optional.** `patched-keystore.jks`, `patched-tv-keystore.jks` | **yes** (small) |
| `/var/log` | nginx logs | no |

`/srv/fdroid` is mixed because `config.yml` and the keystores inside it must
be backed up while the APKs and archive (tens of GB) are rebuildable. Section
7 separates them.

Keystore lookup order, which is what makes the move safe: the app checks
`/keys/<name>` first and falls back to `/srv/fdroid/<name>`. **If `/keys` is
empty nothing changes**, so you can mount it long before you move anything
into it.

Within `/srv/fdroid` after the split:

| Subpath | Backup? | Why |
|---|---|---|
| `config.yml` | yes | repo identity + keystore passwords |
| `keystore.p12` | yes | repo signing key — moving this one needs a `config.yml` edit, see 7.3 |
| `metadata/` | yes | hand-maintained app metadata |
| `patches/`, `patches-tv/` | no | re-downloaded from the patch sources |
| `repo/`, `patched/repo/`, `patched-tv/repo/` | no | republished by a builder run |
| `archive/` | no | bounded by `ARCHIVE_KEEP`, rebuildable |
| `tmp/` | no | deleted after every successful run |
| `.requests/`, `.status/` | no | transient |

---

## 4. Environment

Variables, with the defaults the image uses when they are unset:

| Name | Default | Meaning |
|---|---|---|
| `TZ` | — | timezone, as now |
| `LOG_LEVEL` | `INFO` | `ERROR`/`WARN`/`INFO`/`DEBUG`/`TRACE` |
| `DB_PATH` | `/app/db` | directory holding `app.db` |
| `REPO_PATH` | `/srv/fdroid` | repo data root |
| `KEYS_PATH` | `/keys` | where keystores are looked for first |
| `RUN_SCHEDULERS` | `true` | `true` = all-in-one (current behaviour). `false` = split: the server runs no loops and UI actions queue request files |
| `ARCHIVE_KEEP` | `3` | newest versions per app to keep in `archive/`; `0` disables pruning |
| `KEEP_PREPARED_APKS` | `false` | `true` keeps the downloaded-APK cache in `tmp/` after a successful run |
| `SERVER_JVM_XMX` | `256m` | admin JVM max heap |
| `BUILDER_JVM_XMX` | `256m` | builder JVM max heap (it orchestrates; the patch child does the heavy work) |
| `PATCH_WORKER_XMX` | `1536m` | max heap of the per-patch child JVM |
| `JVM_MAX_METASPACE` | `128m` | metaspace cap for server and builder |
| `JVM_THREAD_STACK` | `512k` | thread stack size |
| `APKSIGNER_XMX` | `1024M` | apksigner's heap (Google's own default) |

**Secrets** (names only): the GitHub token is **not** an environment variable
— it is stored in the SQLite database and set through the admin UI's Settings
page. Keystore passwords live in `/srv/fdroid/config.yml`, which `fdroid init`
generates and which must never be committed.

> On the brief's "passwords in `config.yml` should come from env vars or a
> mounted file": `config.yml` is fdroidserver's own file, and it reads
> `keystorepass`/`keypass` directly from it with no env indirection, so this
> cannot be changed from our side without patching fdroidserver. What is
> fixed instead is the thing that actually mattered — the file is no longer
> downloadable (section 0), and it is `chmod 600` as `fdroid init` creates it.

---

## 5. The split: separating serving from building

Opt-in. Until you do this, the always-on container keeps doing the patching
itself, exactly as today.

### 5.1 Switch the server to serve-only

Add to the `fdroid-repo` quadlet:

```ini
Environment=RUN_SCHEDULERS=false
```

From then on the server runs no scheduler loops, and the admin UI's "Patch
now" / "Check now" buttons write request files instead of patching in
process. **Set up 5.2 and 5.3 before or at the same time as this**, otherwise
nothing will ever build.

### 5.2 Nightly timer

`fdroid-builder.container`:

```ini
[Unit]
Description=F-Droid repo builder (one-shot)

[Container]
ContainerName=fdroid-builder
Image=ghcr.io/bugsbunny-25/custom-repo:main
Exec=builder run --full
Network=proxy.network
Environment=TZ=America/New_York LOG_LEVEL=INFO ARCHIVE_KEEP=3
Volume=/var/mnt/config/apps/custom-fdroid/fdroid-data:/srv/fdroid
Volume=/var/mnt/config/apps/custom-fdroid/db:/app/db
Volume=/var/mnt/config/apps/custom-fdroid/keys:/keys
PodmanArgs=--memory=2500m

[Service]
Type=oneshot
```

`fdroid-builder.timer`:

```ini
[Unit]
Description=Nightly F-Droid repo build

[Timer]
OnCalendar=*-*-* 03:30:00
Persistent=true
RandomizedDelaySec=15m

[Install]
WantedBy=timers.target
```

### 5.3 On-demand trigger from the admin UI

`fdroid-builder-request.path`:

```ini
[Unit]
Description=Watch for F-Droid build requests from the admin UI

[Path]
DirectoryNotEmpty=/var/mnt/config/apps/custom-fdroid/fdroid-data/.requests
Unit=fdroid-builder-request.service

[Install]
WantedBy=paths.target
```

`fdroid-builder-request.service`:

```ini
[Unit]
Description=Run the F-Droid builder for queued requests

[Service]
Type=oneshot
ExecStart=/usr/bin/podman start --attach fdroid-builder-request-run
```

Point that at a one-shot container running `builder run --requests-only`
(a second `.container` quadlet, same volumes as 5.2, `Exec=builder run
--requests-only`).

Why `DirectoryNotEmpty` works here: the builder **claims** requests by moving
them out of `.requests/` into `.status/claimed/`, so the directory really does
become empty and the unit re-arms. The claimed/failed subdirectories are
deliberately kept under `.status/`, not inside `.requests/`, because a
subdirectory counts as an entry for `DirectoryNotEmpty` and would keep the
unit permanently triggered.

No container socket, no extra privileges, and the app never talks to podman.

---

## 6. Memory

Measured on the built image (arm64, Apple `container`), always-on container
idle, 45 s after start-up, from the container's own cgroup:

| | |
|---|---|
| `anon` (real working set) | **151 MiB** |
| `file` (page cache, reclaimable) | 135 MiB |
| `memory.current` (anon + cache) | 290 MiB |

Compare the measured 510 MiB idle before this change (JVM RSS ~497 MiB).

Recommended caps:

| Container | `--memory` | Why |
|---|---|---|
| `fdroid-repo` (server, split mode) | `512m` | idle ~151 MiB; headroom for request spikes and page cache |
| `fdroid-repo` (all-in-one) | `2500m` | it still spawns the patch worker, which needs ~1.76 GiB |
| `fdroid-builder` | `2500m` | `BUILDER_JVM_XMX=256m` plus a `PATCH_WORKER_XMX=1536m` child plus apksigner's 1 GiB, not all at once |

Do not set `--memory=2g` on anything that patches: a YouTube-class run (81
patches) peaks around 1.76 GiB in the worker alone, which is what has been
getting OOM-killed under the current 2 GB cap.

`memory.peak` during a full patch run is worth re-reading on your own
hardware once a builder run has happened:
`podman exec fdroid-builder cat /sys/fs/cgroup/memory.peak`.

**On the brief's "idle in the tens of MB" target for the split:** not reached,
and the measurement says why. Of the 151 MiB idle, nginx with two workers is
single-digit MiB; essentially all the rest is the JVM's floor (heap, metaspace,
thread stacks, Netty) with `-Xmx256m` already applied. So stage 3 (a Rust
admin server) is the only way to get into the tens of MB, and it would save
roughly 120–140 MiB. Whether that is worth a rewrite of working code is your
call — on a 10 GB VM it is about 1.4% of RAM.

---

## 7. Data migration

Current layout: everything under
`/var/mnt/config/apps/custom-fdroid/fdroid-data` (22 GB), on the disk that is
backed up nightly.

Target: the small must-keep material stays on the backed-up disk; the bulk
moves to one that is not backed up.

**Clients see no change at any point**: same URLs, same `repo_url`, same
keystores, same signatures, same index fingerprint. Nothing below regenerates
a key.

### 7.1 Preparation (no downtime)

```bash
# A real backup of the small stuff, before touching anything.
cd /var/mnt/config/apps/custom-fdroid
sudo tar czf ~/fdroid-keys-backup.tgz \
  fdroid-data/config.yml \
  fdroid-data/keystore.p12 \
  fdroid-data/patched-keystore.jks \
  fdroid-data/patched-tv-keystore.jks \
  fdroid-data/patched/config.yml \
  fdroid-data/patched/keystore.p12 \
  fdroid-data/patched-tv/config.yml \
  fdroid-data/patched-tv/keystore.p12 \
  db
```

Record the current index fingerprint so you can prove it is unchanged later:

```bash
cd /var/mnt/config/apps/custom-fdroid/fdroid-data/repo
python3 - <<'EOF'
import zipfile, subprocess, fnmatch
with zipfile.ZipFile('index-v1.jar') as z:
    n = next(m for m in z.namelist() if fnmatch.fnmatch(m, 'META-INF/*.RSA'))
    open('/tmp/sig.bin','wb').write(z.read(n))
print(subprocess.run(['keytool','-printcert','-file','/tmp/sig.bin'],
                     capture_output=True, text=True).stdout)
EOF
```

Keep that SHA-256. It must be identical at the end. (If `keytool` is not on
the host, run the same thing inside the container.)

### 7.2 Move the patched keystores to `/keys` (reversible, ~instant)

```bash
sudo systemctl stop fdroid-repo
cd /var/mnt/config/apps/custom-fdroid
sudo mkdir -p keys
sudo cp fdroid-data/patched-keystore.jks keys/
sudo cp fdroid-data/patched-tv-keystore.jks keys/
```

Add to the quadlet:

```ini
Volume=/var/mnt/config/apps/custom-fdroid/keys:/keys
```

Start it, then confirm from the log that it picked them up:

```bash
sudo systemctl start fdroid-repo
podman logs fdroid-repo | grep 'Using keystore'
# expect: Using keystore patched-keystore.jks from /keys
```

Note these are **copies**. Only once the log shows `/keys` should you remove
the originals — and there is no need to hurry, because the lookup prefers
`/keys` anyway.

**Rollback:** remove the `Volume=` line and restart. The originals (or the
tarball from 7.1) are still in `fdroid-data`.

### 7.3 Move the repo keystore (optional, needs a config edit)

`keystore.p12` is referenced by `config.yml`'s `keystore:` key, which
fdroidserver resolves relative to the repo directory. To move it out:

```bash
sudo systemctl stop fdroid-repo
sudo cp /var/mnt/config/apps/custom-fdroid/fdroid-data/keystore.p12 \
        /var/mnt/config/apps/custom-fdroid/keys/
sudo sed -i 's|^keystore: keystore.p12$|keystore: /keys/keystore.p12|' \
        /var/mnt/config/apps/custom-fdroid/fdroid-data/config.yml
sudo systemctl start fdroid-repo
```

Then force an index rebuild and re-check the fingerprint from 7.1:

```bash
podman exec fdroid-repo sh -c 'cd /srv/fdroid && fdroid update -c'
```

Do the same for `patched/config.yml` and `patched-tv/config.yml` if you want
their keystores moved too.

**Rollback:** `sed` the line back to `keystore: keystore.p12` and restart; the
original file is still there.

**Verify before you trust it:** if the fingerprint differs, stop and restore
`config.yml` from the 7.1 tarball — a changed fingerprint means clients will
reject the repo.

### 7.4 Move the bulk data to a non-backed-up disk

With `D=/var/mnt/bulk/custom-fdroid` as the new disk:

```bash
sudo systemctl stop fdroid-repo
sudo mkdir -p $D
# -a preserves ownership and timestamps; fdroid cares about neither, but
# mtimes make the before/after comparison readable.
sudo rsync -a --info=progress2 \
  /var/mnt/config/apps/custom-fdroid/fdroid-data/ $D/
```

Point the quadlet's repo volume at `$D` instead, start, and check
`/repo/index-v1.jar` still downloads and the fingerprint matches.

Keep the old directory until you are satisfied — that is the rollback: point
the volume back and restart.

Once satisfied, exclude `$D` from the backup job and keep backing up only
`db/`, `keys/`, and `$D/config.yml`, `$D/metadata/`, `$D/patched/config.yml`,
`$D/patched-tv/config.yml`.

### 7.5 Let the archive shrink

`ARCHIVE_KEEP=3` (the default) prunes `archive/` on the next run, which is
where most of the 16 GB goes. It is applied by a builder run, or by the
all-in-one container's patch loop.

The prune reads `archive/index-v1.json` to work out which app and version each
file is, because the filenames here (`{releaseId}-{assetName}.apk` for GitHub
assets) carry neither. If that index does not exist yet, the run logs that and
deletes nothing — so if the first pass reclaims nothing, check for the index
and let one `fdroid update` complete first.

To see what it would do before letting it run, set `ARCHIVE_KEEP=0` (disables
pruning) and count:

```bash
ls /var/mnt/config/apps/custom-fdroid/fdroid-data/archive/*.apk \
  | sed 's/_[0-9]*\.apk$//' | sort | uniq -c | sort -rn
```

Anything with a count above 3 loses its oldest versions. They are
rebuildable; clients lose the ability to install versions older than the
newest three from the archive repo.

---

## 8. Checklist

- [ ] Block `/config.yml` and `*.p12`/`*.jks` at SWAG (section 0)
- [ ] Check nginx access logs for prior fetches of those paths
- [ ] Decide on key rotation
- [ ] Deploy the new image (safe with current defaults)
- [ ] Confirm `/repo/index-v1.jar` fingerprint unchanged
- [ ] Confirm the F-Droid app refreshes and can update an app
- [ ] Confirm `/patched/repo` and `/patched-tv/repo` still work
- [ ] Raise `--memory` to 2500m so patch runs stop being OOM-killed
- [ ] Switch to a `:vX.Y.Z` tag (optional)
- [ ] Mount `/keys` and move the patched keystores (7.2)
- [ ] Split serving from building (section 5)
- [ ] Move bulk data off the backed-up disk (7.4)

---

## 9. Request and status file format

Both live under `$REPO_PATH` (`/srv/fdroid`), which the server and the builder
both mount. Paths:

```
.requests/<id>.json       pending request   (server writes, builder claims)
.status/builder.json      run status        (builder writes, server reads)
.status/builder.lock      advisory lock     (builder only)
.status/claimed/<id>.json requests the current run owns
.status/failed/<id>.json  requests whose run failed (newest 20 kept)
```

Ids are `yyyyMMdd'T'HHmmssSSS-<6 random chars>`, e.g.
`20261113T030222216-2iv75u` — sortable, so filename order is request order.

### 9.1 Request

Written atomically (temp file + rename), so a `.path` unit never sees a
partial file.

```json
{
  "id": "20261113T030222216-2iv75u",
  "kind": "patch",
  "requestedAt": "2026-11-13T03:02:22.216807300Z",
  "targetId": null,
  "version": null,
  "versionPageUrl": null,
  "repo": null,
  "source": "admin-ui"
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | string | matches the filename stem |
| `kind` | enum | see below |
| `requestedAt` | string | ISO-8601 instant, UTC |
| `targetId` | string or null | required for `patch-target*` and `patch-version*` |
| `version` | string or null | required for `patch-version*` |
| `versionPageUrl` | string or null | required for `patch-version*` |
| `repo` | string or null | `owner/name`, narrows a `github` request |
| `source` | string | free text, e.g. `admin-ui` |

`kind` is one of: `full`, `github`, `patch`, `patch-tv`, `patch-target`,
`patch-target-tv`, `patch-version`, `patch-version-tv`, `patch-source`,
`patch-source-tv`, `housekeeping`.

These strings are the contract — they are pinned with `@SerialName` in
`BuildJob.kt` so a Kotlin rename cannot change them silently, and there is a
test asserting it. Unknown fields are ignored and every optional field has a
default, so a file written by a different build still decodes.

You can queue one by hand:

```bash
D=/var/mnt/config/apps/custom-fdroid/fdroid-data
ID=$(date -u +%Y%m%dT%H%M%S000)-manual
printf '{"id":"%s","kind":"full","requestedAt":"%s","source":"manual"}\n' \
  "$ID" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  | sudo tee "$D/.requests/$ID.json" > /dev/null
```

### 9.2 Status

Rewritten (temp file + atomic rename) at every step transition, so a reader
never sees a half-written document.

```json
{
  "state": "SUCCEEDED",
  "run_id": "20261113T030222216-2iv75u",
  "started_at": "2026-11-13T03:05:00Z",
  "finished_at": "2026-11-13T03:11:42Z",
  "current_step": null,
  "steps": [
    {
      "name": "github-releases",
      "state": "ok",
      "message": "downloaded 2 APK(s), index updated",
      "started_at": "2026-11-13T03:05:01Z",
      "finished_at": "2026-11-13T03:06:20Z"
    }
  ],
  "error": null,
  "request_ids": ["20261113T030222216-2iv75u"],
  "kinds": ["full"],
  "index_updated": true,
  "github_downloaded": 2,
  "github_repo": null,
  "patched_count": 1
}
```

Note the keys are **snake_case** here, because this is also what
`GET /api/builder/status` returns to the admin UI.

| `state` | Meaning |
|---|---|
| `IDLE` | no run has happened yet (also what a missing/corrupt file reads as) |
| `RUNNING` | a run is in progress; `current_step` says where |
| `SUCCEEDED` | finished, every step ok |
| `FAILED` | finished, at least one step failed; see `error` and `steps[]` |
| `SKIPPED_LOCKED` | reserved; a builder that loses the lock exits without writing |

Step `state` is `running`, `ok`, `failed` or `skipped`. Step names:
`init-repos`, `github-releases`, `patch-sources`, `patch-sources-tv`,
`patch-mobile`, `patch-tv`, `prune-archive`, `clean-intermediates`.

A failing step does not abort the run — a GitHub outage should not stop the
patch sweep, and neither should stop the archive being bounded — but the run
as a whole is then reported `FAILED` and the builder exits 1.

For alerting, this is the one-liner:

```bash
python3 -c "import json;s=json.load(open('$D/.status/builder.json'));print(s['state'],s.get('error') or '')"
```
