# blipit

Blipit error monitoring for Java and Kotlin. Know your app broke before your users tell you.

## Install

Gradle (Kotlin DSL):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.blipit:blipit:0.1.0")
}
```

Maven:

```xml
<repository>
  <id>jitpack</id>
  <url>https://jitpack.io</url>
</repository>

<dependency>
  <groupId>io.blipit</groupId>
  <artifactId>blipit</artifactId>
  <version>0.1.0</version>
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

Both values are on the project's Keys page at app.blipit.io. Call `Blipit.flush()` before the process exits so queued events are delivered.

## Spring Boot

The same `Blipit.init(...)` call works: put it at the top of `main` before `SpringApplication.run`, or in a `@PostConstruct` method of a `@Configuration` class. Uncaught exceptions in request handlers are then reported.

## Manual capture

```java
Blipit.captureException(err);
Blipit.captureMessage("cache miss rate above 50%");
Blipit.setUser("42", "ana@example.com");
Blipit.setTag("region", "ap-southeast-1");
Blipit.addBreadcrumb("warmed 120 cache keys");
Blipit.captureSecurity("login_failed", "ana@example.com");
```

## Performance

Pass `.tracesSampleRate(0.2)` to the builder and requests show up on the Performance page.

Docs: https://docs.blipit.io/java
