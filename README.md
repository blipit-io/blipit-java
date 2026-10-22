# blipit

Blipit error monitoring for Java and Kotlin, built on blipit-nuggets, Blipit's own crash-reporting core. No dependencies, Java 8 or newer. Know your app broke before your users tell you.

## Install

Gradle (Kotlin DSL):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.blipit-io:blipit-java:v0.2.0")
}
```

Maven:

```xml
<repository>
  <id>jitpack</id>
  <url>https://jitpack.io</url>
</repository>

<dependency>
  <groupId>com.github.blipit-io</groupId>
  <artifactId>blipit-java</artifactId>
  <version>v0.2.0</version>
</dependency>
```

## Quick start

Call `init` once at startup, before the app starts serving.

```java
import io.blipit.Blipit;
import io.blipit.BlipitOptions;

Blipit.init(BlipitOptions.builder()
    .key("<public key>")
    .project(<project id>)
    .environment("production")
    .release("myapp@1.4.2")
    .build());
```

Both values are on the project's Keys page at app.blipit.io.

- Uncaught exceptions on any thread are written to an offline queue on disk before the default handler runs, then sent. Anything not delivered (network down, process killed) is sent on the next start. Set the folder with `.cacheDir(new File(...))`; the default is `java.io.tmpdir/blipit-<project>`.
- A shutdown hook flushes for up to 2 seconds; `Blipit.flush()` does the same on demand and returns whether everything was delivered.
- A 429 from ingest pauses sending for `Retry-After` seconds; server errors are retried with backoff.
- Events carry the OS, the Java version, release and environment, and go to `/api/v1/events` with the key in `X-Blipit-Key`.

## Spring Boot

The same `Blipit.init(...)` call works: put it at the top of `main` before `SpringApplication.run`, or in a `@PostConstruct` method of a `@Configuration` class. Uncaught exceptions in request handlers are then reported.

## Manual capture

```java
Blipit.captureException(err);
Blipit.captureMessage("cache miss rate above 50%");
Blipit.captureMessage("disk almost full", Level.WARNING);
Blipit.setUser("42", "ana@example.com");
Blipit.setTag("region", "ap-southeast-1");
Blipit.setExtra("tenant", "acme");
Blipit.addBreadcrumb("warmed 120 cache keys");
Blipit.addBreadcrumb("GET /api/cart 500", "http", Level.ERROR);
Blipit.captureSecurity("login_failed", "ana@example.com");
```

Login attempts (`captureSecurity`) need the project's secret key (init with it on the server) and the Scale plan; with the public key ingest refuses them with 403 `security_needs_secret_key`.

## Pulse

`.tracesSampleRate(...)` is still accepted. The Java SDK does not instrument requests by itself; send spans with any OpenTelemetry exporter to `https://in.blipit.io/v1/traces` with the `X-Blipit-Key` header (JSON protocol).

## Upgrading to 0.2

- The level enum of the old wrapped library becomes `io.blipit.Level`.
- `setUser(User)` becomes `setUser(id, email)` or `setUser(id, email, username)`; `addBreadcrumb(Breadcrumb)` becomes `addBreadcrumb(message, category, level)`.
- `Blipit.dsn(...)` is gone: there is no DSN, the key goes in a header.
- `captureException` and `captureMessage` return the event id; `flush` returns a boolean.

Docs: https://docs.blipit.io/java
