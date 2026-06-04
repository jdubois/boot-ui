# Try the sample app

> **Security warning:** These convenience commands download a script from GitHub, clone this repository, build it, pull
> Docker images and an Ollama model, and run a Spring Boot app on your machine. Review the script first and run it only
> in a trusted local development environment.

Prerequisites: Git, Java 17 or later, and Docker or a Docker-compatible engine for the sample app's PostgreSQL, Redis,
and Ollama services.

macOS / Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/jdubois/boot-ui/main/scripts/run-sample.sh | bash
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/jdubois/boot-ui/main/scripts/run-sample.ps1 | iex
```

The scripts clone into `./boot-ui` unless that directory already exists, build with `-DskipTests`, then start
`bootui-sample-app` with the `dev` profile. Spring Boot starts PostgreSQL, Redis, and Ollama from
`bootui-sample-app/compose.yaml`; the first startup also pulls the small `qwen2.5:0.5b` chat model when missing. Open
<http://localhost:8080/bootui> after startup. If you already cloned the repository, run `./scripts/run-sample.sh` or
`.\scripts\run-sample.ps1` from its root to reuse that checkout.
