---
layout: home

hero:
  name: BootUI
  text: Local developer console for Spring Boot
  tagline: A Spring Boot 4 starter that embeds an instant, local-only developer console into your app. No Node.js, no npm, no external service.
  image:
    src: /logo.svg
    alt: BootUI
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started
    - theme: alt
      text: Explore features
      link: /guide/features
    - theme: alt
      text: View on GitHub
      link: https://github.com/jdubois/boot-ui

features:
  - icon: ☕
    title: Drop-in Spring Boot starter
    details: Add one dependency. BootUI is served by your app at /bootui/ and packages its Vue UI into the starter, so consuming applications never need a JavaScript toolchain.
    link: /guide/getting-started
    linkText: Add the dependency
  - icon: 🧭
    title: 25+ purpose-built panels
    details: Health, Metrics, Memory, Heap Dump, Configuration, Beans, Conditions, Mappings, Traces, Security, Caches, Dev Services, AI usage, and more — grouped exactly like the in-app menu.
    link: /guide/features
    linkText: See every panel
  - icon: 🔒
    title: Local-only by design
    details: Activates only for dev/local profiles or DevTools, rejects non-loopback callers, masks secrets, and disables itself in production unless explicitly forced on.
    link: /guide/configuration
    linkText: Safety model
  - icon: 🎛️
    title: Configurable & read-only aware
    details: Every panel can be hidden or made read-only, and the whole console can be locked down with a single property. Runtime overrides never touch your source config.
    link: /guide/properties
    linkText: Property reference
---

<div style="max-width: 960px; margin: 3rem auto 0; padding: 0 1.5rem;">

## What is BootUI?

**BootUI** is a **Spring Boot 4 starter** that adds an embedded, local-only developer console to your
application. It is served by the host application at `/bootui/`, uses internal `/bootui/api/**` endpoints, and
packages the browser UI into the starter so consuming applications do not need Node.js or npm.

![BootUI overview](/images/bootui-overview.png)

### Add it in seconds

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open <http://localhost:8080/bootui>.

</div>
