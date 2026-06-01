# Getting started

## 1) Prerequisites

- Java 25
- Spring Boot 4.x application
- Maven or your application's Maven Wrapper

## 2) Add the starter dependency

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 3) Run your app in development mode

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

BootUI also activates automatically when `spring-boot-devtools` is on the classpath. To force it on or off:

```properties
bootui.enabled=AUTO
bootui.enabled=ON
bootui.enabled=OFF
```

`prod` and `production` profiles disable BootUI unless `bootui.enabled=ON` is set. Invalid `bootui.enabled` values fail
closed and keep BootUI disabled.

## 4) Open BootUI

Visit <http://localhost:8080/bootui>.

::: tip Local only
BootUI is intended for local development. By default it rejects non-loopback callers and disables itself for
`prod` / `production` profiles. See [Configuration & safety](./configuration) for the full safety model.
:::

## Next steps

- [Features](./features) — explore every panel
- [Configuration & safety](./configuration) — tune activation, exposure, and read-only behaviour
- [Troubleshooting](./troubleshooting) — fix common setup issues
