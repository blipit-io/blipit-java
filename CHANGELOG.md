## 0.2.1

- Platform mismatch: when Blipit refuses data because the key belongs to a project for another platform (HTTP 409 platform_mismatch), the SDK logs one clear error and stops sending for the rest of the run.

## 0.2.0

- Built on blipit-nuggets, Blipit's own crash-reporting core. The library has no dependencies at all and targets Java 8 bytecode, so it runs on any JVM from 8 up and on Android.
- Events go to `/api/v1/events` in Blipit's event format with the key in `X-Blipit-Key`.
- Uncaught exceptions are written to an offline queue on disk (`cacheDir`, default `java.io.tmpdir/blipit-<project>`) before the handler returns, then sent; anything left over is sent on the next start. A shutdown hook flushes for up to 2 seconds.
- Rate limits: a 429 pauses sending for `Retry-After` seconds; server errors retry with backoff.
- `setUser(id, email, username)`, `setExtra`, `setContext`, `addBreadcrumb(message, category, level)`; cause chains are attached as `extra.caused_by`.
- Source changes: `io.blipit.Level` replaces the old level enum; `setUser(User)`, `addBreadcrumb(Breadcrumb)` and `Blipit.dsn` are removed; `captureException` and `captureMessage` return the event id; `flush` returns whether the queue is empty.

## 0.1.2

- Events carry the `maven:io.blipit:blipit` package so the dashboard can show update notices.
