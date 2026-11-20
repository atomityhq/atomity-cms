# API reference

Base path: `/api/v1`. In the Docker stack the backend is at `http://localhost:8080`. The admin UI
reaches it through its own server (`http://localhost:3000/api/v1/...`), which turns the session cookie
into a bearer token; external websites call the backend directly and only need the public endpoints.

## Envelope and errors

Every JSON response is `{"data": ..., "meta": ..., "errors": [...]}`; `meta` carries pagination.
An error response has only `errors`:

```json
{"errors": [{"errorCode": "NOT_FOUND", "message": "Content not found", "correlationId": "fd12dffc-…"}]}
```

`correlationId` also comes back in the `X-Correlation-ID` header and is printed on every backend log line.

| HTTP | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | A body field is malformed. The message lists every problem, e.g. `fields.applyUrl must be a valid URL starting with http:// or https://` |
| 400 | `UNKNOWN_CONTENT_TYPE` | `type` is not one of `news`, `career`, `docs`, `kb` |
| 400 | `BAD_REQUEST` | Bad query parameter (`sort`, `direction`, `status`, paging) |
| 401 | `UNAUTHORIZED` | Admin endpoint without a valid token |
| 404 | `NOT_FOUND` | No such item or type. For public reads this also covers drafts and archived items. |
| 409 | `SLUG_TAKEN` | Another item of the same type already uses the slug |
| 409 | `VERSION_CONFLICT` | The item changed since the editor loaded it |
| 422 | `NOT_PUBLISHABLE` | Publishing (or saving published content) without a title, content or a required field |

## Public endpoints (no authentication)

### `GET /content-types`

The four types with their fields and destination metadata. The admin form is generated from this.

```json
{"data": [{
  "key": "career", "displayName": "Career opening",
  "description": "Job openings, internships and other career opportunities.",
  "destination": {"label": "website", "route": "/careers"},
  "fields": [
    {"name": "department", "label": "Department", "type": "text", "required": true, "options": []},
    {"name": "employmentType", "label": "Employment type", "type": "select", "required": true,
     "options": ["full-time", "part-time", "intern", "contract"]}
  ]}]}
```

Field `type` is one of `text`, `url`, `date`, `boolean`, `number`, `select`. `required` only matters for
publishing. `destination` is **descriptive only**: `route` is `null` for `docs` and `kb` because
no route has been decided, and the CMS never delivers anything to a destination.

### `GET /content/{type}`

Published items of one type, newest first. Query parameters: `q` (substring search over title, excerpt,
slug and body), `sort` (`publishedAt` default, `updatedAt`, `title`, plus `order` for `docs` only),
`direction` (`desc` default, `asc`), `page` (from 0), `size` (default 10, max 100). An unknown type is a `404`.

For `docs`, passing `sort=order` sorts items by `fields.order` in ascending numeric order with missing or
null `order` values placed last (`NULLS LAST`), breaking ties by `title` ascending and then `id` ascending.
Passing `sort=order` on any other content type (or with `direction=desc`) returns `400 BAD_REQUEST`.

```json
{"data": [{
   "id": "6d346aea-…", "type": "career", "title": "Demo: Java Backend Intern",
   "slug": "demo-java-backend-intern", "excerpt": "Help build the content API.",
   "fields": {"department": "Engineering", "location": "Mannheim", "employmentType": "intern",
              "applyUrl": "https://example.org/apply", "closingDate": "2030-12-31", "remote": true},
   "publishedAt": "2026-10-30T12:49:45.579554Z", "updatedAt": "2026-10-30T12:49:45.579556Z"}],
 "meta": {"page": 0, "size": 10, "totalElements": 1, "totalPages": 1}}
```

The list carries `fields` so it can be rendered from one request, but not the body. The order of keys
inside `fields` is not guaranteed; use `/content-types` for the field order.

### `GET /content/{type}/slug/{slug}`

One published item, including `content` (a Tiptap/ProseMirror JSON document). Editor-only data (status,
version, creation time) is never exposed. Drafts, unpublished and archived items answer `404`.

Images in a body are nodes like `{"type": "image", "attrs": {"src": "/api/v1/media/<id>/file", "alt": "…"}}`;
prefix the backend address to fetch them: `GET /media/{id}/file` is public, immutable and cacheable.

## Admin endpoints (`Authorization: Bearer <token>`)

### `POST /auth/login`

`{"email": "admin@example.com", "password": "admin"}` → `{"data": {"token": "…", "expiresAt": "…"}}`.
Repeated failures are rate limited.

### `POST /posts` (create, `201`) and `PUT /posts/{id}` (update)

```json
{
  "type": "career",
  "title": "Java Backend Intern",
  "slug": "java-backend-intern",
  "excerpt": "Help build the content API.",
  "content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "…"}]}]},
  "fields": {"department": "Engineering", "location": "Mannheim", "employmentType": "intern",
             "applyUrl": "https://example.org/apply"},
  "version": 0
}
```

- `type` is required on create and cannot change later (omit it, or repeat the same one, on update).
- `content` is required; everything else is optional on a draft. An empty slug is derived from the title.
- `version` is required on update: the version you loaded. A stale one returns `409 VERSION_CONFLICT`.
- `fields` is validated on every save: unknown names, malformed URLs, dates other than `YYYY-MM-DD`,
  values outside a `select`'s options and non-integers in a number field are rejected, **but empty or missing
  values are fine**. Required fields are only enforced on publish. Text is limited to 500 characters,
  numbers are whole numbers from 0 to 1,000,000.
- Content that is already published must stay publishable: saving it without a title, content or required
  field returns `422 NOT_PUBLISHABLE`.
- Response: the full item (`id`, `type`, `title`, `slug`, `excerpt`, `content`, `fields`, `status`, `version`,
  `publishedAt`, `createdAt`, `updatedAt`).

### `GET /posts`, `GET /posts/{id}`

`GET /posts?type=career&status=DRAFT&q=intern&sort=updatedAt&direction=desc&page=0&size=10`. `status` is
`DRAFT`, `PUBLISHED` or `ARCHIVED`; `sort` is `updatedAt` (default), `title` or `publishedAt`. Rows contain
`id, type, title, slug, excerpt, status, publishedAt, updatedAt`.

### Lifecycle

| Request | Effect |
|---|---|
| `POST /posts/{id}/publish` | `PUBLISHED`; needs a title, some content and every required field of the type (`422 NOT_PUBLISHABLE` otherwise, with a message such as "Add the Department field and the Application URL field before publishing."). `publishedAt` is set the first time and kept afterwards. |
| `POST /posts/{id}/unpublish` | Back to `DRAFT` (also restores an archived item). The public API stops returning it. |
| `POST /posts/{id}/archive` | `ARCHIVED`. The public API stops returning it. |

### Media

`POST /media` (multipart field `file`; PNG, JPEG, GIF or WebP up to 10 MB, detected from the bytes),
`GET /media`, `PATCH /media/{id}` (alt text), `DELETE /media/{id}` (refused with `409 MEDIA_IN_USE` while an
item embeds it). Files are served publicly at `GET /media/{id}/file`.
