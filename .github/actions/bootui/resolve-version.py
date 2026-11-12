#!/usr/bin/env python3
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import importlib.util

sys.dont_write_bytecode = True

try:
    import tomllib
except ModuleNotFoundError:
    tomllib = None


MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
STABLE_VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
GRADLE_COORDINATE = re.compile(
    r"(?:com\.julien-dubois\.bootui|io\.github\.jdubois\.bootui)"
    r":[^:'\"\s]+:([^'\"\s)]+)"
)
SKIPPED_DIRECTORIES = {".git", ".gradle", ".m2", "build", "node_modules", "target"}


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    raise SystemExit(1)


def build_files(root):
    for directory, names, files in os.walk(root):
        names[:] = [name for name in names if name not in SKIPPED_DIRECTORIES]
        for name in files:
            if name in ("pom.xml", "build.gradle", "build.gradle.kts"):
                yield Path(directory) / name


def maven_properties(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return {}
    properties = {}
    namespace = MAVEN_NAMESPACE["m"] if root.tag.startswith("{") else ""
    prefix = f"{{{namespace}}}" if namespace else ""
    for child in root:
        if child.tag != prefix + "properties":
            continue
        for property_element in child:
            name = property_element.tag.rsplit("}", 1)[-1]
            properties[name] = (property_element.text or "").strip()
    profiles = root.findall(".//m:profiles/m:profile", MAVEN_NAMESPACE) if namespace else root.findall(".//profiles/profile")
    for profile in profiles:
        profile_id = profile.findtext(prefix + "id")
        if profile_id != "bootui-ci":
            continue
        profile_properties = profile.find(prefix + "properties")
        if profile_properties is not None:
            for child in profile_properties:
                name = child.tag.rsplit("}", 1)[-1]
                properties.setdefault(name, (child.text or "").strip())
    project_version = root.findtext(prefix + "version")
    if project_version:
        properties["project.version"] = project_version
    return properties


def maven_versions(path, inherited):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return set()
    namespace = MAVEN_NAMESPACE["m"] if root.tag.startswith("{") else ""
    prefix = f"{{{namespace}}}" if namespace else ""
    properties = dict(inherited)
    properties.update(maven_properties(path))
    versions = set()
    dependencies = root.findall(".//m:dependency", MAVEN_NAMESPACE) if namespace else root.findall(".//dependency")
    for dependency in dependencies:
        group = dependency.findtext(prefix + "groupId")
        if group not in ("com.julien-dubois.bootui", "io.github.jdubois.bootui"):
            continue
        version = (dependency.findtext(prefix + "version") or "").strip()
        visited = set()
        while STABLE_VERSION.fullmatch(version) is None:
            match = re.fullmatch(r"\$\{([^}]+)\}", version)
            if match is None or match.group(1) not in properties or match.group(1) in visited:
                break
            visited.add(match.group(1))
            replacement = properties[match.group(1)].strip()
            if replacement == version:
                break
            version = replacement
        if STABLE_VERSION.fullmatch(version):
            versions.add(version)
    return versions


def gradle_versions(path):
    versions = set()
    try:
        source = path.read_text(encoding="utf-8")
    except UnicodeError:
        return versions
    for match in GRADLE_COORDINATE.finditer(source):
        version = match.group(1)
        if version.endswith(('"', "'")):
            version = version[:-1]
        if STABLE_VERSION.fullmatch(version):
            versions.add(version)
    properties = {}
    for properties_file in (path.parent / "gradle.properties", path.parent.parent / "gradle.properties"):
        if not properties_file.is_file():
            continue
        for line in properties_file.read_text(encoding="utf-8", errors="ignore").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                properties[key.strip()] = value.strip()
    for match in re.finditer(
        r"(?m)[\"'](?:com\.julien-dubois\.bootui|io\.github\.jdubois\.bootui)"
        r"[\"']\s*,\s*[\"'][^\"']+[\"']\s*,\s*[\"']([^\"']+)[\"']",
        source,
    ):
        version = expand_property(match.group(1), properties)
        if STABLE_VERSION.fullmatch(version):
            versions.add(version)
    for match in re.finditer(r"\$[A-Za-z_][A-Za-z0-9_.]*|\$\{[^}]+\}", source):
        token = match.group(0)
        candidate = expand_property(token, properties)
        coordinate_start = source.rfind("bootui-", max(0, match.start() - 200), match.start())
        if coordinate_start >= 0 and STABLE_VERSION.fullmatch(candidate):
            versions.add(candidate)
    return versions


def expand_property(value, properties):
    visited = set()
    while STABLE_VERSION.fullmatch(value) is None:
        match = re.fullmatch(r"\$([A-Za-z_][A-Za-z0-9_.]*)|\$\{([^}]+)\}", value)
        if match is None:
            return value
        key = match.group(1) or match.group(2)
        if key in visited or key not in properties:
            return value
        visited.add(key)
        value = properties[key]
    return value


def catalog_versions(root):
    catalog = root / "gradle" / "libs.versions.toml"
    if tomllib is None or not catalog.is_file():
        return set()
    try:
        data = tomllib.loads(catalog.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, tomllib.TOMLDecodeError):
        return set()
    versions = set()
    values = data.get("versions", {})
    for library in data.get("libraries", {}).values():
        if not isinstance(library, dict):
            continue
        module = library.get("module", "")
        if not module.startswith(("com.julien-dubois.bootui:", "io.github.jdubois.bootui:")):
            continue
        version = library.get("version", "")
        if isinstance(version, dict):
            version = values.get(version.get("ref"), "")
        if STABLE_VERSION.fullmatch(str(version)):
            versions.add(str(version))
    return versions


configured = sys.argv[1] if len(sys.argv) > 1 else "auto"
if configured != "auto":
    if STABLE_VERSION.fullmatch(configured) is None:
        fail("bootui-version must be auto or a stable major.minor.patch version.")
    print(configured)
    raise SystemExit

root = Path(os.environ.get("GITHUB_WORKSPACE") or Path.cwd()).resolve()
if (os.environ.get("BOOTUI_PHASE") == "run"
        and os.environ.get("BOOTUI_APP_COMMAND", "auto") == "auto"
        and (root / "pom.xml").is_file()):
    spec = importlib.util.spec_from_file_location("maven_context", Path(__file__).with_name("maven-context.py"))
    context_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(context_module)
    context = context_module.load_context(root / "pom.xml")
    if context is not None:
        try:
            selected, established = context.selected()
        except ValueError:
            # Startup reports ambiguous profiles; version-only phases keep their existing discovery.
            established = False
        if established:
            selected_versions = context.versions(selected)
            if len(selected_versions) != 1:
                fail("Could not resolve one stable BootUI version from the selected Maven context; set bootui-version explicitly.")
            print(selected_versions.pop())
            raise SystemExit
found = set()
for build in build_files(root):
    if build.name == "pom.xml":
        inherited = {}
        ancestors = list(build.parent.parents)
        candidates = list(reversed([parent for parent in ancestors if root == parent or root in parent.parents]))
        candidates.append(build.parent)
        for parent in candidates:
            pom = parent / "pom.xml"
            if pom != build and pom.is_file():
                inherited.update(maven_properties(pom))
        found.update(maven_versions(build, inherited))
    else:
        found.update(gradle_versions(build))
found.update(catalog_versions(root))

if not found:
    fail("Could not detect a stable BootUI dependency version; set bootui-version explicitly.")
if len(found) != 1:
    fail(f"Multiple BootUI versions were detected ({', '.join(sorted(found))}); set bootui-version explicitly.")
print(found.pop())
