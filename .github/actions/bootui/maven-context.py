#!/usr/bin/env python3
"""Conservative root-POM selection, not an effective Maven model."""
import os
from pathlib import Path
import re
import shlex
import sys
import xml.etree.ElementTree as ET


GROUPS = {"com.julien-dubois.bootui", "io.github.jdubois.bootui"}
ARTIFACTS = {"bootui-spring-boot-starter", "bootui-quarkus"}
STABLE_VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
PROFILE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


def notice(message):
    print(f"BootUI: {message} Set app-command explicitly for custom Maven configuration.", file=sys.stderr)


class MavenContext:
    def __init__(self, path):
        self.path = Path(path)
        self.root = ET.parse(path).getroot()
        self.prefix = self.root.tag[:-len("project")]
        if self.root.tag not in ("project", "{http://maven.apache.org/POM/4.0.0}project"):
            raise ValueError("Not a Maven project")
        self.profiles = self.root.findall(self.query("profiles/profile"))
        self.base = self.properties(self.root)
        version = self.root.findtext(self.query("version"))
        if version:
            self.base["project.version"] = version.strip()

    def query(self, path):
        return "/".join(self.prefix + part for part in path.split("/"))

    def text(self, element, name, default=""):
        return (element.findtext(self.query(name)) or default).strip()

    def properties(self, element):
        properties = element.find(self.query("properties"))
        return {} if properties is None else {
            child.tag.rsplit("}", 1)[-1]: (child.text or "").strip() for child in properties
        }

    @staticmethod
    def expand(value, properties):
        visited = set()
        for _ in range(20):
            if "${" not in value or value in visited or len(value) > 4096:
                break
            visited.add(value)
            value = re.sub(r"\$\{([^}]+)}", lambda match: properties.get(match[1], match[0]), value)
        return value

    def dependencies(self, element, properties):
        return [
            dependency for dependency in element.findall(self.query("dependencies/dependency"))
            if self.expand(self.text(dependency, "groupId"), properties) in GROUPS
            and self.expand(self.text(dependency, "artifactId"), properties) in ARTIFACTS
            and self.expand(self.text(dependency, "scope", "compile"), properties) in ("compile", "runtime")
        ]

    def profile_properties(self, profile):
        return {**self.base, **(self.properties(profile) if profile is not None else {})}

    def versions(self, profile):
        properties = self.profile_properties(profile)
        elements = [self.root] + ([profile] if profile is not None else [])
        managed = {}
        dependencies = {}
        for element in elements:
            for dependency in element.findall(self.query("dependencyManagement/dependencies/dependency")):
                key = (self.expand(self.text(dependency, "groupId"), properties),
                       self.expand(self.text(dependency, "artifactId"), properties))
                managed[key] = self.text(dependency, "version")
            for dependency in self.dependencies(element, properties):
                key = (self.expand(self.text(dependency, "groupId"), properties),
                       self.expand(self.text(dependency, "artifactId"), properties))
                dependencies[key] = dependency
        versions = set()
        for key, dependency in dependencies.items():
            version = self.expand(self.text(dependency, "version") or managed.get(key, ""), properties)
            if STABLE_VERSION.fullmatch(version) is None:
                return set()
            versions.add(version)
        return versions

    def selected(self, conventional_only=False):
        runtime = self.dependencies(self.root, self.base)
        candidates = [
            profile for profile in self.profiles
            if self.dependencies(profile, self.profile_properties(profile))
        ]
        conventional = next(
            (profile for profile in self.profiles if self.text(profile, "id") == "bootui-ci"), None
        )
        if conventional is not None and not (runtime or conventional in candidates):
            notice("The bootui-ci profile does not establish a direct BootUI runtime dependency.")
            conventional = None
        if conventional_only:
            return conventional, False
        configuration = self.path.parent / ".mvn/maven.config"
        try:
            arguments = shlex.split(os.environ.get("MAVEN_ARGS", ""))
            if configuration.is_file():
                arguments += shlex.split(configuration.read_text(encoding="utf-8"), comments=True)
        except ValueError:
            notice("Maven options could not be inspected; retaining the existing launch command.")
            return conventional, False
        if any(arg.startswith(("-P", "--activate-profiles", "-D", "--define", "-f", "--file", "-s", "--settings"))
               for arg in arguments):
            notice("Existing Maven model/profile options are preserved; automatic profile inference is disabled.")
            return conventional, False
        if any(profile.find(self.query("activation")) is not None for profile in self.profiles):
            notice("Maven profile activation rules are preserved; automatic profile inference is disabled.")
            return conventional, False
        if conventional is not None:
            return conventional, True
        if runtime and self.versions(None):
            return None, True
        if runtime:
            candidates = [
                profile for profile in self.profiles
                if self.dependencies(profile, self.profile_properties(profile)) or self.versions(profile)
            ]
        if any(PROFILE_ID.fullmatch(self.text(profile, "id")) is None
               or len(self.text(profile, "id")) > 80 for profile in candidates):
            raise ValueError("The BootUI Maven profile ID is unsupported; set app-command explicitly.")
        if len(candidates) > 1:
            raise ValueError(
                "Multiple Maven profiles can supply BootUI (" +
                ", ".join(self.text(profile, "id") for profile in candidates[:10]) +
                "); set app-command explicitly, "
                "for example './mvnw -Pdev spring-boot:run'."
            )
        if candidates:
            profile = candidates[0]
            return profile, True
        notice("BootUI is not declared as a root runtime dependency; retaining the existing launch command.")
        return None, False


def load_context(path):
    try:
        return MavenContext(path)
    except (ET.ParseError, ValueError):
        notice("The root POM could not be inspected; retaining the existing launch command.")
        return None


if __name__ == "__main__":
    context = load_context(Path(sys.argv[1]) / "pom.xml")
    try:
        profile, _ = context.selected(conventional_only=sys.argv[2] == "test") if context else (None, False)
    except ValueError as failure:
        print(f"::error::{failure}", file=sys.stderr)
        raise SystemExit(1)
    if profile is not None:
        print(context.text(profile, "id"))
        print(f"BootUI: selected Maven profile {context.text(profile, 'id')}.", file=sys.stderr)
