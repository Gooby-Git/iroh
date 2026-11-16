#!/usr/bin/env python3
"""Finds pack mixins that touch methods Iroh @Overwrites (P1-1).

An @Overwrite replaces the whole method body, so another mod's @Inject/@Redirect/@ModifyArg/@WrapOperation
aimed at a call INSIDE that method no longer finds its target (owo braid.TextureUtilMixin, 2026-11-15).
Iroh side: javap of the built (intermediary) jar, @Overwrite methods + @Mixin targets.
Pack side: every refmap entry of the form L<class>;<name>(<desc>) in every mod jar (method= targets and
INVOKE targets alike, so a hit means "look at this mixin", not "this breaks").
Usage: python3 -I tools/overwrite_scan.py [mods_dir]   (needs javap on PATH)
"""
import io, json, os, re, subprocess, sys, tempfile, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODS = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.local/share/atlauncher/instances/Vulkan/mods")
jar = sorted(p for p in os.listdir(os.path.join(ROOT, "build/libs")) if p.startswith("iroh-") and "sources" not in p)[0]
jar = os.path.join(ROOT, "build/libs", jar)

# 1. Iroh overwrites: (target class, method name+desc)
over = {}
with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(jar) as z:
    names = [n for n in z.namelist() if n.endswith(".class") and "/mixin/" in n]
    for n in names:
        z.extract(n, tmp)
    for n in names:
        out = subprocess.run(["javap", "-v", "-p", os.path.join(tmp, n)], capture_output=True, text=True).stdout
        targets = re.findall(r'class L([^;]+);', " ".join(re.findall(r'value=\[[^\]]*\]', out)))
        targets += [t.replace(".", "/") for t in re.findall(r'targets=\["([^"]+)"', out)]
        # method blocks: "  public static void name(...);\n    descriptor: (..)V ... RuntimeVisibleAnnotations ... Overwrite"
        for m in re.finditer(r"\n  [^\n]*?(\w+)\([^\n]*\);\n    descriptor: (\S+)\n(.*?)(?=\n  [^ \n]|\Z)", out, re.S):
            if "org.spongepowered.asm.mixin.Overwrite" in m.group(3):
                for t in targets:
                    over.setdefault((t, m.group(1) + m.group(2)), n)

# 2. Pack refmaps
hits = {}
def scan(zf, label):
    for n in zf.namelist():
        if n.endswith("refmap.json"):
            try:
                rm = json.loads(zf.read(n))
            except Exception:
                continue
            for mixin, maps in (rm.get("mappings") or {}).items():
                for v in maps.values():
                    m = re.match(r"L([^;]+);(\w+|<init>)(\(.*)", v)
                    if m and (m.group(1), m.group(2) + m.group(3)) in over:
                        hits.setdefault((m.group(1), m.group(2) + m.group(3)), set()).add(f"{label}: {mixin}")
        elif n.startswith("META-INF/jars/") and n.endswith(".jar"):
            try:
                scan(zipfile.ZipFile(io.BytesIO(zf.read(n))), label + "!" + os.path.basename(n))
            except Exception:
                pass
for f in sorted(os.listdir(MODS)):
    if f.endswith(".jar") and not f.startswith("iroh-"):
        try:
            scan(zipfile.ZipFile(os.path.join(MODS, f)), f)
        except Exception as e:
            print("ERR", f, e)

print(f"Iroh @Overwrite methods: {len(over)}; touched by pack refmaps: {len(hits)}")
for (cls, meth), who in sorted(hits.items()):
    print(f"\n{cls}.{meth}  (Iroh: {over[(cls, meth)]})")
    for w in sorted(who):
        print("   ", w)
