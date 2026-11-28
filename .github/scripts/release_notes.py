#!/usr/bin/env python3
"""Rawr 17U fork: release notes = the tag message's first line, then the CHANGELOG.md section of this version
("## <name>", unwrapped so GitHub does not break lines mid-sentence), then .github/release-footer.md.
Usage: release_notes.py <name> <tag message file> > notes.md"""
import re
import sys

name, tag_msg_file = sys.argv[1], sys.argv[2]
tag_msg = open(tag_msg_file, encoding="utf-8").read().strip()
summary = tag_msg.splitlines()[0] if tag_msg else ""

section, inside = [], False
for line in open("CHANGELOG.md", encoding="utf-8").read().splitlines():
    if line.startswith("## "):
        if inside:
            break
        inside = line[3:].strip() == name
        continue
    if inside:
        section.append(line)

# Join wrapped lines: a new block starts at a blank line, a heading or a list item.
out = []
for line in section:
    new_block = line == "" or line.startswith("#") or re.match(r"^\s*[-*] ", line)
    if out and not new_block and out[-1] != "" and not out[-1].startswith("#"):
        out[-1] = out[-1].rstrip() + " " + line.strip()
    else:
        out.append(line)
body = "\n".join(out).strip()

parts = []
if summary:
    parts.append(summary)
if body:
    parts.append("## What's new\n\n" + body)
elif len(tag_msg.splitlines()) > 1:
    parts.append(tag_msg)  # no CHANGELOG section: the whole tag message, as before
else:
    sys.stderr.write(f"warning: no CHANGELOG.md section '## {name}'\n")
parts.append(open(".github/release-footer.md", encoding="utf-8").read().strip())
print("\n\n".join(parts))
