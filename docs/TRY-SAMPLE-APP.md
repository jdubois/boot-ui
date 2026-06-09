# Try the sample app

> **Security warning:** These convenience commands download a script from GitHub, clone this repository, build it, and
> run a Spring Boot app on your machine. Review the script first and run it only in a trusted local development
> environment.

Prerequisites: Git and Java 17 or later. The sample app's `dev` profile runs **Docker-free** (in-memory H2 database, a
simple in-memory cache, and disabled Spring AI), so no Docker engine is required for current releases.

macOS / Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/jdubois/boot-ui/main/scripts/run-sample.sh | bash
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/jdubois/boot-ui/main/scripts/run-sample.ps1 | iex
```

The scripts list `main` plus the five most recent release tags and ask which version to build and run; the most recent
tag is selected by default (press Enter to accept, or set `BOOTUI_REF` to choose non-interactively). They clone into
`./boot-ui` unless that directory already exists, build with `-DskipTests`, then start `bootui-sample-app` with the
`dev` profile. Open <http://localhost:8080/bootui> after startup. If you already cloned the repository, run
`./scripts/run-sample.sh` or `.\scripts\run-sample.ps1` from its root to reuse that checkout.

In Docker-free mode most panels work normally (Configuration, Database, Spring Data, Flyway, Liquibase, Spring Cache);
the Chat and AI Usage panels report that AI is unavailable, and Dev Services lists no containers.

Want the full experience with PostgreSQL, Redis, and Ollama (a Docker-compatible engine is required)? Run the sample app
with the `docker` profile instead — see the [sample app README](../bootui-sample-app/README.md#run-it-with-docker) for
details. Note that releases published before the Docker-free default also start those Docker services under the `dev`
profile, so building one of those older tags requires a Docker engine.
