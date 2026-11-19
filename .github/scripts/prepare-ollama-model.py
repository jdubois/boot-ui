#!/usr/bin/env python3
"""Pull and verify the Docker sample's configured local model in one bounded CI attempt."""

import json
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[2]
PROPERTIES = ROOT / "bootui-spring-sample-app/src/main/resources/application-docker.properties"


def configuration(path):
    keys = ("spring.ai.ollama.base-url", "spring.ai.ollama.chat.model")
    values = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.strip().partition("=")
        if separator and key in keys:
            if key in values or not value.strip() or "${" in value:
                raise ValueError(f"{path}: expected one literal, nonempty value for {key}")
            values[key] = value.strip()
    if any(key not in values for key in keys):
        raise ValueError(f"{path}: must configure both Ollama base URL and chat model")
    base_url, model = (values[key] for key in keys)
    url = urlsplit(base_url)
    if (
        url.scheme != "http"
        or url.hostname not in ("localhost", "127.0.0.1", "::1")
        or url.username is not None
        or url.password is not None
        or url.path not in ("", "/")
        or url.query
        or url.fragment
    ):
        raise ValueError(f"{path}: Ollama base URL must be a local HTTP origin")
    if any(character.isspace() for character in model):
        raise ValueError(f"{path}: Ollama model must not contain whitespace")
    return base_url.rstrip("/"), model


def request(url, seconds, payload=None):
    command = [
        "curl",
        "--silent",
        "--show-error",
        "--fail-with-body",
        "--max-time",
        str(seconds),
        url,
    ]
    if payload is not None:
        command.extend(["--header", "Content-Type: application/json", "--data", json.dumps(payload)])
    result = subprocess.run(
        command, check=True, stdout=subprocess.PIPE, text=True, timeout=seconds + 5
    )
    response = json.loads(result.stdout)
    if not isinstance(response, dict) or "error" in response:
        raise ValueError(f"Ollama request to {url} failed: {response}")
    return response


def prepare(path=PROPERTIES):
    base_url, model = configuration(path)
    print(f"Pulling and verifying Ollama model {model}", flush=True)
    result = request(f"{base_url}/api/pull", 180, {"model": model, "stream": False})
    if result.get("status") != "success":
        raise ValueError(f"Ollama pull did not report success for {model}: {result}")
    inventory = request(f"{base_url}/api/tags", 15)
    models = inventory.get("models")
    if not isinstance(models, list) or any(
        not isinstance(entry, dict) or not isinstance(entry.get("name"), str)
        for entry in models
    ):
        raise ValueError(f"Ollama returned an invalid local model inventory: {inventory}")
    # Ollama adds :latest when the configured name omits a tag.
    expected = model if ":" in model.rsplit("/", 1)[-1] else f"{model}:latest"
    if not any(entry["name"] == expected for entry in models):
        raise ValueError(f"Ollama model {expected} is not available locally after the pull")
    print(f"Verified Ollama model {expected} is available locally", flush=True)


if __name__ == "__main__":
    try:
        prepare()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"::error::Ollama model preparation failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stdout:
            print(error.stdout, file=sys.stderr)
        sys.exit(1)
