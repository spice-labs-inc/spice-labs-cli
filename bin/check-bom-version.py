#!/usr/bin/env python3
"""Enforce (and advise) the spice-bom version-bump policy.

The BOM's public contract is the set of dependencies it manages — coordinates and the
versions they are pinned to. Relative to the last released BOM (the highest `bom-v*` tag)
the required bump is:

  * major  — a managed artifact was REMOVED (breaks consumers relying on it)
  * minor  — a managed artifact was ADDED (purely additive)
  * patch  — a managed version CHANGED
  * none   — the managed set is identical

This script compares `bom/pom.xml` in the working tree against that tag and checks that the
in-repo BOM version (its `-SNAPSHOT` base) has been bumped by at least the required amount.

Usage:
  check-bom-version.py            # enforce; non-zero exit if the bump is insufficient
  check-bom-version.py --advise   # print the recommended bump/version, always exit 0
"""

import re
import subprocess
import sys
import xml.etree.ElementTree as ET

BOM_PATH = "bom/pom.xml"
TAG_PREFIX = "bom-v"


def local(tag):
    return tag.rsplit("}", 1)[-1]


def find(elem, name):
    for child in elem:
        if local(child.tag) == name:
            return child
    return None


def resolve(version, props):
    # Resolve ${prop} references (a few passes handles property-to-property chains).
    for _ in range(5):
        m = re.fullmatch(r"\$\{([^}]+)\}", version or "")
        if not m:
            break
        version = props.get(m.group(1), version)
    return version


def parse_pom(text):
    """Return (own_version, {"g:a": resolved_version, ...})."""
    root = ET.fromstring(text)
    own_version = (find(root, "version").text or "").strip() if find(root, "version") is not None else ""

    props = {}
    props_el = find(root, "properties")
    if props_el is not None:
        for p in props_el:
            props[local(p.tag)] = (p.text or "").strip()

    managed = {}
    dm = find(root, "dependencyManagement")
    deps = find(dm, "dependencies") if dm is not None else None
    if deps is not None:
        for dep in deps:
            if local(dep.tag) != "dependency":
                continue
            g = find(dep, "groupId")
            a = find(dep, "artifactId")
            v = find(dep, "version")
            if g is None or a is None:
                continue
            key = f"{(g.text or '').strip()}:{(a.text or '').strip()}"
            managed[key] = resolve((v.text or "").strip() if v is not None else "", props)
    return own_version, managed


def parse_semver(v):
    base = v.split("-", 1)[0]
    parts = base.split(".")
    return tuple(int(x) for x in (parts + ["0", "0", "0"])[:3])


def fmt(t):
    return ".".join(str(x) for x in t)


def bump(base, level):
    M, m, p = base
    return {"major": (M + 1, 0, 0), "minor": (M, m + 1, 0), "patch": (M, m, p + 1), "none": base}[level]


def latest_bom_tag():
    out = subprocess.run(
        ["git", "tag", "--list", f"{TAG_PREFIX}*"], capture_output=True, text=True
    ).stdout.split()
    versioned = []
    for t in out:
        m = re.fullmatch(rf"{re.escape(TAG_PREFIX)}(\d+\.\d+\.\d+)", t)
        if m:
            versioned.append((parse_semver(m.group(1)), t))
    if not versioned:
        return None, None
    versioned.sort()
    ver, tag = versioned[-1]
    return tag, ver


def git_show(ref, path):
    r = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"error: cannot read {path} at {ref}: {r.stderr.strip()}")
    return r.stdout


def main():
    advise = "--advise" in sys.argv[1:]

    tag, base_ver = latest_bom_tag()
    with open(BOM_PATH, encoding="utf-8") as fh:
        cur_version, cur_managed = parse_pom(fh.read())

    if tag is None:
        print(f"No {TAG_PREFIX}* release yet — skipping BOM version-bump enforcement "
              f"(current in-repo version {cur_version}).")
        return 0

    _, base_managed = parse_pom(git_show(tag, BOM_PATH))

    added = sorted(set(cur_managed) - set(base_managed))
    removed = sorted(set(base_managed) - set(cur_managed))
    changed = sorted(k for k in set(cur_managed) & set(base_managed)
                     if cur_managed[k] != base_managed[k])

    if removed:
        level = "major"
    elif added:
        level = "minor"
    elif changed:
        level = "patch"
    else:
        level = "none"

    required = bump(base_ver, level)
    cur_base = parse_semver(cur_version)

    def detail():
        lines = []
        for k in removed:
            lines.append(f"  - removed  {k} (was {base_managed[k]})")
        for k in added:
            lines.append(f"  + added    {k} = {cur_managed[k]}")
        for k in changed:
            lines.append(f"  ~ changed  {k}: {base_managed[k]} -> {cur_managed[k]}")
        return "\n".join(lines)

    if level == "none":
        print(f"BOM unchanged vs {tag} ({fmt(base_ver)}); no version bump required.")
        return 0

    print(f"BOM changed vs {tag} ({fmt(base_ver)}) — {level} change:")
    print(detail())
    print(f"Required BOM version: >= {fmt(required)} (current in-repo: {cur_version}).")

    if advise:
        return 0

    if cur_base >= required:
        print(f"OK: {cur_version} satisfies the required {level} bump.")
        return 0

    print(
        f"\nERROR: bom/pom.xml changed but its version was not bumped enough.\n"
        f"  Set <version> in {BOM_PATH} to at least {fmt(required)}-SNAPSHOT "
        f"(a {level} bump over the released {fmt(base_ver)}).",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
