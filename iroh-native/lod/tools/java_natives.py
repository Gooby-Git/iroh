#!/usr/bin/env python3
# Prints the decorated JNI names for every `static native` method of LodNative (and LodSeedNative with arg `seed`), sorted.
import re, sys
repo = sys.argv[1]; seed = len(sys.argv) > 2 and sys.argv[2] == "seed"
pkg = "dev/elemore/iroh/lod/nat"
names = []
for cls in ["LodNative"] + (["LodSeedNative"] if seed else []):
    src = open(f"{repo}/src/main/java/{pkg}/{cls}.java").read()
    pre = "Java_" + pkg.replace("_", "_1").replace("/", "_") + "_" + cls + "_"
    names += [pre + n for n in re.findall(r"^\s*(?:public\s+)?static\s+native\s+[^;(]*?\b(\w+)\s*\(", src, re.M)]
print("\n".join(sorted(names)))
