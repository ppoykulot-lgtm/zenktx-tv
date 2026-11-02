"""Static check: every @drawable/@string/@color/@style/@layout reference exists.

Not a substitute for a Gradle build, but it catches the common mistakes
(renamed or deleted resources) without an Android toolchain installed.
"""
import glob
import os
import re

RES_ROOT = "android/app/src/main/res"
SRC_ROOT = "android/app/src/main"

have = set()
for path in glob.glob(RES_ROOT + "/**/*.xml", recursive=True):
    base = os.path.basename(path)[:-4]
    folder = os.path.basename(os.path.dirname(path))
    if folder.startswith("values"):
        text = open(path, encoding="utf8").read()
        for m in re.finditer(r'<(string|color|style|dimen|array)\s+name="([^"]+)"', text):
            have.add(m.group(2))
    else:
        have.add(base)

for folder in glob.glob(RES_ROOT + "/drawable*"):
    for f in glob.glob(folder + "/*"):
        have.add(os.path.basename(f).split(".")[0])

# Media3's controller ids live in the library, not in this project.
LIBRARY_PREFIXES = ("exo_",)

missing = set()
files = glob.glob(SRC_ROOT + "/**/*.xml", recursive=True)
files += glob.glob(SRC_ROOT + "/**/*.kt", recursive=True)

for path in files:
    if os.sep + "res" + os.sep + "values" + os.sep in path:
        continue
    text = open(path, encoding="utf8").read()
    for m in re.finditer(r"@(?:drawable|string|color|style|layout|id)/([A-Za-z0-9_.]+)", text):
        name = m.group(1)
        if name.startswith(LIBRARY_PREFIXES):
            continue
        if name not in have:
            missing.add((path, name))

for path, name in sorted(missing):
    print("MISSING", name, "->", path)
print("unresolved references:", len(missing))
raise SystemExit(1 if missing else 0)
