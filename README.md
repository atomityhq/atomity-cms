# Atomity CMS

Open-source CMS for the content that sits next to the product: **news**, **career openings**,
**documentation** and **knowledge-base** articles and **events**.

An editor picks a content type, fills in guided fields, saves a draft, validates it and clicks
Publish. Published content is then available through a public, read-only API, so separate websites
can fetch and render it. **The CMS never controls how those websites look.**

> **Status: initial skeleton.** It works end to end locally (create, validate, publish, read through
> the public API, show on an example website) and is meant to be extended by contributors.
> It is not production-ready, and nothing in this repository talks to any Atomity production system.

- **Frontend**: Next.js 16 (App Router), React 19, TypeScript, Tailwind CSS v4, Tiptap
- **Backend**: Spring Boot 3.4, Java 21, Gradle
- **Database**: PostgreSQL 16, schema managed by Flyway
- **Media**: images on the local filesystem (a Docker volume), no external storage needed

It is built on the same foundation as Atomity's Blog CMS (same stack, layout and conventions), with
one shared content model instead of a blog-only one.

## What it does and does not do

| It does | It does not |
|---|---|
| Five content types with their own guided fields and validation | Render, host or deploy any website |
| Draft → published → archived lifecycle, with validation on publish | Push content anywhere: no webhooks, no builds, no cache revalidation |
| A public read API that only ever returns published content | Manage several users or roles (one admin account) |
| Rich text (Tiptap JSON) and local image uploads | Schedule posts, keep revisions, or translate content |
| An example website (`examples/mock-consumer`) that consumes the API | Decide where a type is shown: the "destination" is only descriptive metadata |

## Content types

| Key | Purpose | Extra fields (**bold** = required to publish) |
|---|---|---|
| `news` | Announcements and updates | none |
| `career` | Job openings and internships | **department**, **location**, **employmentType** (`full-time`, `part-time`, `intern`, `contract`), **applyUrl**, closingDate, remote |
| `docs` | Guides and onboarding documentation | **section**, order |
| `kb` | Troubleshooting and knowledge-base articles | **symptom**, product, affectedVersions |
| `event` | Community events, meetups and workshops | **venue**, **startsOn**, registrationUrl, online |
| `event` | Community events, meetups and workshops | **venue**, **startsOn**, registrationUrl, online |

Every item also has a title, slug, excerpt, rich-text body, status and timestamps. A draft can be
saved with anything missing; **publishing** needs a title, some content and the type's required
fields. Slugs are unique within a type. The set of types lives in one Java class,
[`ContentTypes`](backend/src/main/java/io/atomity/generalcms/content/ContentTypes.java); see
[Adding a content type](docs/adding-a-content-type.md).

## Admin UI

Sign in, then **Content**: a list you can filter by type and status and search, and **New content**, which asks for a
type first. The editor has a title, slug, excerpt, the Tiptap rich-text editor with images from the media library,
and a details panel generated from the type's fields (text, URL, date, number, yes/no and select inputs; required
ones are marked). **Save draft** keeps anything incomplete; **Publish** explains what is missing instead of failing
silently; published items offer **Update** and **Unpublish**, and the "…" menu archives. Unsaved changes are
guarded, and a version conflict (someone else saved) is reported rather than overwritten. **Media** is the image
library.

## How publishing works

```text
 editor (admin UI)            backend                       website (any consumer)
 ─────────────────            ───────────────────────       ──────────────────────
 pick type, fill fields ─►    POST /api/v1/posts   (draft)
 Publish               ─►    POST /api/v1/posts/{id}/publish
                              validates → status PUBLISHED
                                                       ◄─    GET /api/v1/content/{type}
                                                       ◄─    GET /api/v1/content/{type}/slug/{slug}
```

"Publish" only changes the item's status in the database. The public API starts returning it; the
website pulls it when it wants to. Nothing is deployed or sent anywhere, and unpublishing or archiving
makes the next request return `404`.

### How a website consumes it

```bash
curl -s localhost:8080/api/v1/content-types                      # the types, their fields and destination hints
curl -s 'localhost:8080/api/v1/content/career?size=10'           # published careers, newest first
curl -s localhost:8080/api/v1/content/news/slug/my-first-post    # one item, including its body
```

The body is a Tiptap/ProseMirror JSON document; the consumer renders it however it likes.
`examples/mock-consumer` shows a small renderer. Fetch on the server: the backend has no CORS
configuration by design. See [docs/api.md](docs/api.md) for every endpoint and response shape.

## Quick start (Docker)

Requires Docker with Compose v2. No JDK, Node or PostgreSQL is needed, and no external accounts.

```bash
cp .env.example .env     # safe local defaults; nothing here points at Atomity infrastructure
make up                  # or: docker compose up --build
```

The first build downloads dependencies and takes a few minutes.

| Service  | URL                                        |
|----------|--------------------------------------------|
| Admin UI | http://localhost:3000                      |
| Backend  | http://localhost:8080/health/readiness     |
| Postgres | `localhost:5432` (credentials from `.env`) |

Sign in with **admin@example.com / admin** (the local default from `.env.example`).

Ports are the same defaults as Blog CMS. If that stack is running, stop it or change `POSTGRES_PORT`,
`BACKEND_PORT` and `FRONTEND_PORT` in `.env`.

### See the whole flow

With the stack running:

```bash
make demo
```

This starts the example website at **http://localhost:4000** and runs
[`seed.mjs`](examples/mock-consumer/seed.mjs), which creates a draft of each type, proves the draft
is not public, shows an incomplete career being refused, publishes everything, and reads it back
through the public API. It exits non-zero if any step misbehaves. Re-running it is safe.

### Logs, stopping, resetting

```bash
docker compose logs -f backend       # follow a service's logs (postgres, backend, frontend, mock-consumer)
make down                            # stop; the database and uploaded images are kept
```

> **Warning, destructive:** `docker compose down -v` also deletes the named volumes `postgres-data`
> (all content) and `media-data` (all uploaded images). Use it only when you want a clean slate.

## Tests

| Command | What it runs | Needs |
|---|---|---|
| `make docker-test` | Backend tests (JUnit 5 + Testcontainers with a real Postgres) in a container | Docker |
| `make docker-check` | Frontend lint, typecheck, Vitest and production build in a container | Docker |
| `make consumer-test` | Unit tests of the example website's renderer | Docker |
| `make test` / `make check` | The same checks with your own toolchain (`check` = backend tests + frontend lint, types, tests, build) | JDK 21, Node ≥ 20.9 with npm ≥ 11.16, Docker |

`make docker-test` mounts the Docker socket so Testcontainers can start Postgres; it is written for
Docker Desktop (macOS, Windows) and may need adjusting on Linux.

## Local development without containers for the apps

```bash
make db                                   # Postgres only, in Docker
make backend                              # Spring Boot on :8080 (profile: local, reads .env)
echo 'BACKEND_API_URL=http://localhost:8080' > frontend/.env.local   # where the frontend finds the backend
make install && make frontend             # Next.js on :3000 (also enables the git hooks)
```

`frontend/.env.local` is ignored by git. The sign-in defaults for local development are the same as in the
Docker stack (**admin@example.com / admin**).

## Configuration

| Variable | App | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | backend | Database connection |
| `ADMIN_EMAIL` | backend | Email of the single admin account |
| `ADMIN_PASSWORD_HASH` | backend | bcrypt hash of the admin password |
| `JWT_SECRET` | backend | Session-token signing key, at least 32 characters |
| `JWT_TTL` | backend | Session length (default `8h`) |
| `MEDIA_STORAGE_DIR` | backend | Directory for uploaded images (default `./data/media`; `/data/media` in Docker, a named volume) |
| `SERVER_PORT` | backend | HTTP port (default `8080`) |
| `BACKEND_API_URL` | frontend | Backend base URL, used server-side only |
| `SESSION_COOKIE_SECURE` | frontend | `false` to allow sign-in over plain HTTP on a host other than localhost |
| `CMS_API_URL` | mock consumer | Where the example website reads content from |

The admin password is never configured in plain text. Generate a hash with:

```bash
docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" 'your-password' | tr -d ':\n'
```

In `.env`, wrap the hash in single quotes: it contains `$`, which Compose would otherwise expand.

## Repository layout

```text
atomity-cms/
├── backend/               Spring Boot API (own Gradle wrapper, Dockerfile)
├── frontend/              Next.js admin UI (own package.json, Dockerfile)
├── examples/mock-consumer/  Zero-dependency Node website that reads the public API
├── docs/                  Architecture, API reference, "adding a content type"
├── .githooks/             pre-commit and pre-push checks (enabled by `make install`)
├── docker-compose.yml     postgres + backend + frontend (+ mock-consumer with --profile demo)
└── Makefile               Shortcuts, run `make help`
```

## Picking up a task

Work in this repository is tracked as [GitHub issues](https://github.com/atomityhq/atomity-cms/issues).
Internship candidates and regular contributors follow the same process, from choosing an issue to a
merged pull request.

```text
pick an issue ──► comment to claim it ──► fork + branch ──► commit ──► push ──► pull request
                                                                                      │
                                                                       review ──► merge to main
```

1. **Choose an issue.** Browse the [open issues](https://github.com/atomityhq/atomity-cms/issues) and
   pick one that has no assignee and no open pull request. Issues labelled `good first issue` or
   `help wanted` are good places to start. If no suitable issue exists, you may report a bug or a
   feature idea with the bug report or feature request form, then wait for a maintainer to confirm the
   scope before you start implementing it.
2. **Claim it.** Comment on the issue that you are taking it, before you start, so nobody duplicates the
   work. Ask scope questions and agree on the approach in that thread: the issue is where scope is settled.
3. **Fork and branch.** Fork the repository, clone your fork, then branch from `main` using the prefixes in
   [CONTRIBUTING.md](CONTRIBUTING.md#workflow) (`feat/`, `fix/`, `chore/`, `docs/`),
   e.g. `git checkout -b fix/head-on-public-routes`.
4. **Set up locally.** Follow the [Quick start](#quick-start-docker) or
   [local development](#local-development-without-containers-for-the-apps), then run `make install`
   (`make hooks` is enough for backend-only work) so the git hooks are active.
5. **Make the change.** One issue is one focused pull request, unless the maintainers agree otherwise in
   the issue. Follow [docs/architecture.md](docs/architecture.md) and the
   [conventions](CONTRIBUTING.md#conventions), and add or update tests for what you changed.
6. **Verify it.** Run `make docker-test` and `make docker-check` (or `make check` with a local toolchain),
   plus `make consumer-test` if you touched `examples/mock-consumer`; see [Tests](#tests). For changes you
   can see, also try them in the running app (`make up`, then `make demo`). The pre-commit hook re-runs the
   checks for the parts you staged, and the pre-push hook refuses direct pushes to `main`.
7. **Commit and push.** Use [Conventional Commits](https://www.conventionalcommits.org/), e.g.
   `fix: allow HEAD on public GET routes`, then push the branch to your fork:
   `git push -u origin fix/head-on-public-routes`.
8. **Open a pull request** against `main` of `atomityhq/atomity-cms`. Fill in the template: what changed,
   how you tested it (the commands you ran and what they showed) and the checklist. Link the issue with
   `Closes #<number>`.
9. **Respond to review.** Discuss in the pull request and push follow-up commits to the same branch. A
   maintainer merges once the review is done.

## Contributing

Start with [docs/architecture.md](docs/architecture.md) and [CONTRIBUTING.md](CONTRIBUTING.md); to choose,
claim and deliver a task, follow [Picking up a task](#picking-up-a-task). Good first pieces: a new content
type, a new field type, better rendering in the example website, more tests (open a feature request first
and wait for a maintainer to confirm the scope). Everything can be developed and tested locally with the
commands above; contributing never needs production access. See also our
[Code of Conduct](CODE_OF_CONDUCT.md) and [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)
