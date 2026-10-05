#!/usr/bin/env python3
"""Checks a staged BootUI Central bundle: the published coordinates and their consumer POMs.

Usage: check-central-bundle.py <bundle-repository-directory> <version>

The directory is the unzipped bundle central-publishing-maven-plugin uploads (a Maven repository layout), as
stage-release-candidate.sh writes it. The check fails unless:

- the bundle holds exactly the published coordinates, each with its POM, jar, sources and javadoc jars and their
  md5 and sha1 checksums, plus the runnable bootui-cli "all" jar;
- every POM is flattened: no <parent>, explicit coordinates, no unresolved property, no dependency management,
  repositories, profiles or build, and the metadata Maven Central requires, identical to the root POM's;
- every dependency has a literal version, and no compile or runtime dependency points at an unpublished BootUI
  artifact (an optional one, such as the agent's shaded bridge, is never resolved by a consumer);
- bootui-spring-boot-starter brings no web stack: no servlet or reactive web-server artifact at compile or
  runtime scope, so the application's own web starter decides between Spring MVC and Spring WebFlux.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

GROUP = "com.julien-dubois.bootui"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

PUBLISHED = (
    "bootui-core",
    "bootui-engine",
    "bootui-ui",
    "bootui-spring-boot-starter",
    "bootui-quarkus",
    "bootui-quarkus-deployment",
    "bootui-cli",
    "bootui-agent",
)

EXTRA_FILES = {"bootui-cli": ("all",)}

URL = "https://github.com/jdubois/boot-ui"
SCM = {
    "connection": "scm:git:https://github.com/jdubois/boot-ui.git",
    "developerConnection": "scm:git:ssh://git@github.com/jdubois/boot-ui.git",
    "url": URL,
}
LICENSE = ("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")

FORBIDDEN_ELEMENTS = (
    "parent",
    "dependencyManagement",
    "repositories",
    "pluginRepositories",
    "profiles",
    "build",
    "modules",
    "distributionManagement",
)

# Artifacts that would choose the application's web stack (WebApplicationType) if the starter brought them.
WEB_STACK_GROUPS = ("org.apache.tomcat.embed", "org.eclipse.jetty", "org.eclipse.jetty.ee11", "io.undertow")
WEB_STACK_ARTIFACTS = {
    ("org.springframework.boot", artifact)
    for artifact in (
        "spring-boot-starter-web",
        "spring-boot-starter-webmvc",
        "spring-boot-starter-tomcat",
        "spring-boot-starter-tomcat-runtime",
        "spring-boot-starter-jetty",
        "spring-boot-starter-jetty-runtime",
        "spring-boot-starter-undertow",
        "spring-boot-starter-webflux",
        "spring-boot-starter-reactor-netty",
        "spring-boot-webmvc",
        "spring-boot-webflux",
        "spring-boot-tomcat",
        "spring-boot-jetty",
        "spring-boot-reactor-netty",
    )
} | {
    ("org.springframework", "spring-webmvc"),
    ("jakarta.servlet", "jakarta.servlet-api"),
    ("io.projectreactor.netty", "reactor-netty-http"),
}


def text(element, path):
    found = element.find(path, NS)
    return None if found is None or found.text is None else found.text.strip()


def check_pom(artifact, version, pom_path, errors):
    def error(message):
        errors.append(f"{artifact}: {message}")

    raw = pom_path.read_text(encoding="utf-8")
    if "${" in raw:
        error("the POM still contains an unresolved ${...} property")
    root = ET.fromstring(raw)
    for element in FORBIDDEN_ELEMENTS:
        if root.find(f"m:{element}", NS) is not None:
            error(f"the published POM must not declare <{element}>")
    for field, expected in (("groupId", GROUP), ("artifactId", artifact), ("version", version)):
        if text(root, f"m:{field}") != expected:
            error(f"<{field}> must be {expected!r}, found {text(root, f'm:{field}')!r}")
    for field in ("name", "description"):
        if not text(root, f"m:{field}"):
            error(f"Maven Central requires a non-empty <{field}>")
    if text(root, "m:url") != URL:
        error(f"<url> must be {URL!r}, found {text(root, 'm:url')!r}")
    licenses = root.findall("m:licenses/m:license", NS)
    if [(text(item, "m:name"), text(item, "m:url")) for item in licenses] != [LICENSE]:
        error("<licenses> must hold exactly the Apache License 2.0")
    developers = root.findall("m:developers/m:developer", NS)
    if not developers or not all(text(item, "m:name") for item in developers):
        error("Maven Central requires <developers> with a named developer")
    for field, expected in SCM.items():
        if text(root, f"m:scm/m:{field}") != expected:
            error(f"<scm><{field}> must be {expected!r}, found {text(root, f'm:scm/m:{field}')!r}")

    for dependency in root.findall("m:dependencies/m:dependency", NS):
        group = text(dependency, "m:groupId")
        name = text(dependency, "m:artifactId")
        dependency_version = text(dependency, "m:version")
        scope = text(dependency, "m:scope") or "compile"
        optional = text(dependency, "m:optional") == "true"
        coordinate = f"{group}:{name}"
        if not group or not name or not dependency_version:
            error(f"dependency {coordinate} must declare groupId, artifactId and a literal version")
        if scope in ("test", "system", "import"):
            error(f"dependency {coordinate} has scope {scope}, which a published POM must not carry")
        transitive = scope in ("compile", "runtime") and not optional
        if group == GROUP and transitive:
            if name not in PUBLISHED:
                error(f"dependency {coordinate} is not published, so consumers cannot resolve it")
            elif dependency_version != version:
                error(f"dependency {coordinate} must be version {version}, found {dependency_version}")
        if artifact == "bootui-spring-boot-starter" and scope in ("compile", "runtime"):
            if (group, name) in WEB_STACK_ARTIFACTS or group in WEB_STACK_GROUPS:
                error(f"the starter must bring no web stack, but declares {coordinate} at {scope} scope")


def check_bundle(directory, version):
    errors = []
    base = Path(directory) / Path(*GROUP.split("."))
    if not base.is_dir():
        return [f"no {GROUP} artifacts under {directory}"]
    staged = sorted(path.name for path in base.iterdir() if path.is_dir())
    if staged != sorted(PUBLISHED):
        errors.append(f"the bundle must hold exactly {sorted(PUBLISHED)}, found {staged}")
    for artifact in PUBLISHED:
        artifact_dir = base / artifact / version
        if not artifact_dir.is_dir():
            continue
        prefix = f"{artifact}-{version}"
        expected = [f"{prefix}.pom", f"{prefix}.jar", f"{prefix}-sources.jar", f"{prefix}-javadoc.jar"]
        expected += [f"{prefix}-{classifier}.jar" for classifier in EXTRA_FILES.get(artifact, ())]
        for name in expected:
            for suffix in ("", ".md5", ".sha1"):
                if not (artifact_dir / (name + suffix)).is_file():
                    errors.append(f"{artifact}: missing {name + suffix}")
        pom = artifact_dir / f"{prefix}.pom"
        if pom.is_file():
            check_pom(artifact, version, pom, errors)
    return errors


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    errors = check_bundle(argv[1], argv[2])
    for message in errors:
        print(f"::error::{message}", file=sys.stderr)
    if errors:
        print(f"The staged Central bundle failed {len(errors)} check(s).", file=sys.stderr)
        return 1
    print(f"The staged Central bundle holds exactly the {len(PUBLISHED)} published coordinates, with flattened POMs.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
