# Setup

## 1) Prerequisites

- Java 17 or later
- Spring Boot 4.x application
- Maven or your application's Maven Wrapper

## 2) Add the starter dependency

```xml
<dependency>
  <groupId>com.julien-dubois.bootui</groupId>
  <artifactId>bootui-spring-boot-starter</artifactId>
  <version>0.5.1</version>
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

Visit: <http://localhost:8080/bootui>
