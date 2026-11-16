#!/usr/bin/env python3
"""Static mixin-conflict scanner for the pack (card P1-1b). No game launch, nothing written to the instance.

Reads every mixin class of Iroh (build/libs jar) and of every active pack jar (nested jars included),
resolves method / at.target strings through each config's refmap to intermediary names, and reports:

  A. every Iroh handler that REMOVES or REPLACES something (@Overwrite, @Redirect, @ModifyConstant,
     @ModifyArg(s), @ModifyVariable, @WrapOperation, @WrapWithCondition, @ModifyExpressionValue,
     @ModifyReceiver, @WrapMethod, and @Inject(cancellable) at HEAD) together with every pack mixin
     that targets the same class + method: injection point, require (own or config defaultRequire),
     priority, and for an @Overwrite whether Iroh's new body still contains the call their INVOKE aims at.
  B. the same-call collisions (two redirects / two constant mods on one call) as a separate list.
  C. with --gl: every call a pack jar's bytecode makes into org/lwjgl/opengl/** , with the owning class and
     whether it sits in a mixin, an entrypoint-ish class or a static initializer (boot-path triage).

Usage: python3 -I tools/injector_scan.py [--mods DIR] [--iroh JAR] [--gl] [--only MODSUBSTR]
Intermediary names are translated to Mojang names for display when the loom mappings file exists.
"""
import io, json, os, re, struct, sys, zipfile, glob, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODS = os.path.expanduser("~/.local/share/atlauncher/instances/Vulkan/mods")
IROH = None
GL = False
ONLY = None
a = sys.argv[1:]
while a:
    x = a.pop(0)
    if x == "--mods": MODS = a.pop(0)
    elif x == "--iroh": IROH = a.pop(0)
    elif x == "--gl": GL = True
    elif x == "--only": ONLY = a.pop(0)
if IROH is None:
    IROH = sorted(p for p in glob.glob(ROOT + "/build/libs/iroh-*.jar") if "sources" not in p)[0]

# ---------------------------------------------------------------- mappings (display only)
CM, MM = {}, {}
for tiny in glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/1.21.1/*/mappings.tiny")):
    for l in open(tiny):
        if l.startswith("c\t"):
            p = l.rstrip("\n").split("\t"); CM[p[2]] = p[3]
        elif l.startswith("\tm\t") or l.startswith("\tf\t"):
            p = l.rstrip("\n").split("\t")
            if len(p) >= 6: MM.setdefault(p[4], p[5])
    break
def tr(s):
    if not isinstance(s, str): return s
    s = re.sub(r"net/minecraft/class_\d+(\$class_\d+)*", lambda m: CM.get(m.group(0), m.group(0)), s)
    return re.sub(r"(method|field)_\d+", lambda m: MM.get(m.group(0), m.group(0)), s)

# ---------------------------------------------------------------- class file parser
def parse_class(b, want_code=False):
    n = struct.unpack(">H", b[8:10])[0]
    cp = [None] * n; i = 10; k = 1
    while k < n:
        t = b[i]
        if t == 1:
            l = struct.unpack(">H", b[i+1:i+3])[0]; cp[k] = ("u", b[i+3:i+3+l].decode("utf8", "replace")); i += 3 + l
        elif t == 3: cp[k] = ("i", struct.unpack(">i", b[i+1:i+5])[0]); i += 5
        elif t == 4: cp[k] = ("f",); i += 5
        elif t in (5, 6): cp[k] = ("l",); i += 9; k += 1
        elif t == 7: cp[k] = ("c", struct.unpack(">H", b[i+1:i+3])[0]); i += 3
        elif t == 8: cp[k] = ("s", struct.unpack(">H", b[i+1:i+3])[0]); i += 3
        elif t in (9, 10, 11): cp[k] = ("r", t) + struct.unpack(">HH", b[i+1:i+5]); i += 5
        elif t == 12: cp[k] = ("nt",) + struct.unpack(">HH", b[i+1:i+5]); i += 5
        elif t == 15: cp[k] = ("mh",); i += 4
        elif t == 16: cp[k] = ("mt",); i += 3
        elif t in (17, 18): cp[k] = ("x",); i += 5
        elif t in (19, 20): cp[k] = ("m",); i += 3
        else: raise ValueError("tag %d" % t)
        k += 1
    U = lambda x: cp[x][1]
    CL = lambda x: U(cp[x][1])
    def ref(x):
        e = cp[x]; nt = cp[e[3]]
        return (CL(e[2]), U(nt[1]), U(nt[2]))
    def cpval(x):
        e = cp[x]
        if e[0] == "u": return e[1]
        if e[0] == "i": return e[1]
        if e[0] == "c": return ("class", U(e[1]))
        return None
    def ev(i):
        t = chr(b[i]); i += 1
        if t in "BCDFIJSZ":
            x = struct.unpack(">H", b[i:i+2])[0]; return cpval(x), i + 2
        if t == "s":
            x = struct.unpack(">H", b[i:i+2])[0]; return U(x), i + 2
        if t == "e":
            ti, ci = struct.unpack(">HH", b[i:i+4]); return ("enum", U(ci)), i + 4
        if t == "c":
            x = struct.unpack(">H", b[i:i+2])[0]; return ("class", U(x)), i + 2
        if t == "@":
            return ann(i)
        if t == "[":
            c = struct.unpack(">H", b[i:i+2])[0]; i += 2; l = []
            for _ in range(c):
                v, i = ev(i); l.append(v)
            return l, i
        raise ValueError(t)
    def ann(i):
        ti, np_ = struct.unpack(">HH", b[i:i+4]); i += 4; d = {}
        for _ in range(np_):
            ni = struct.unpack(">H", b[i:i+2])[0]; i += 2
            v, i = ev(i); d[U(ni)] = v
        return ("ann", U(ti), d), i
    def attrs(i, out_ann=None, out_code=None):
        c = struct.unpack(">H", b[i:i+2])[0]; i += 2
        for _ in range(c):
            ni, ln = struct.unpack(">HI", b[i:i+6]); i += 6
            nm = U(ni)
            if out_ann is not None and nm in ("RuntimeInvisibleAnnotations", "RuntimeVisibleAnnotations"):
                j = i; na = struct.unpack(">H", b[j:j+2])[0]; j += 2
                for _ in range(na):
                    (_, t, d), j = ann(j); out_ann.append((t, d))
            if out_code is not None and nm == "Code":
                out_code.append(b[i:i+ln])
            i += ln
        return i
    acc, this, sup = struct.unpack(">HHH", b[i:i+6]); i += 6
    nif = struct.unpack(">H", b[i:i+2])[0]; i += 2 + 2 * nif
    nf = struct.unpack(">H", b[i:i+2])[0]; i += 2
    fields = []
    for _ in range(nf):
        a_, ni, di = struct.unpack(">HHH", b[i:i+6]); i += 6
        an = []; i = attrs(i, an); fields.append((U(ni), U(di), an))
    nm_ = struct.unpack(">H", b[i:i+2])[0]; i += 2
    methods = []
    for _ in range(nm_):
        a_, ni, di = struct.unpack(">HHH", b[i:i+6]); i += 6
        an = []; code = []; i = attrs(i, an, code if want_code else None)
        methods.append(dict(name=U(ni), desc=U(di), acc=a_, ann=an, code=code[0] if code else None))
    can = []; i = attrs(i, can)
    return dict(this=CL(this), sup=CL(sup) if sup else None, ann=can, methods=methods, fields=fields, cp=cp, ref=ref, U=U, CL=CL, acc=acc)

def invokes(cls, m):
    """(opcode, owner, name, desc) for every invoke / get/put field in a method body."""
    code = m["code"]
    if not code: return []
    ml, cl = struct.unpack(">HH", code[0:4]); L = struct.unpack(">I", code[4:8])[0]
    bc = code[8:8+L]; out = []; p = 0
    while p < len(bc):
        op = bc[p]
        if op in (0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9):
            out.append((op,) + cls["ref"](struct.unpack(">H", bc[p+1:p+3])[0])); p += 5 if op == 0xb9 else 3
        elif op == 0xba: p += 5
        elif op == 0xc4: p += 6 if bc[p+1] == 0x84 else 4
        elif op == 0xaa:
            p += 1; p = (p + 3) & ~3
            lo, hi = struct.unpack(">ii", bc[p+4:p+12]); p += 12 + 4 * (hi - lo + 1)
        elif op == 0xab:
            p += 1; p = (p + 3) & ~3
            np_ = struct.unpack(">i", bc[p+4:p+8])[0]; p += 8 + 8 * np_
        elif op in (0x10, 0x12) or op in (0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3a,0xa9,0xbc): p += 2
        elif op in (0x11, 0x13, 0x14, 0x84, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7) or 0x99 <= op <= 0xa8: p += 3
        elif op in (0xc5,): p += 4
        elif op in (0xc8, 0xc9): p += 5
        else: p += 1
    return out

# ---------------------------------------------------------------- mixin extraction
INJ_KINDS = {
    "org/spongepowered/asm/mixin/Overwrite": "Overwrite",
    "org/spongepowered/asm/mixin/injection/Inject": "Inject",
    "org/spongepowered/asm/mixin/injection/Redirect": "Redirect",
    "org/spongepowered/asm/mixin/injection/ModifyArg": "ModifyArg",
    "org/spongepowered/asm/mixin/injection/ModifyArgs": "ModifyArgs",
    "org/spongepowered/asm/mixin/injection/ModifyConstant": "ModifyConstant",
    "org/spongepowered/asm/mixin/injection/ModifyVariable": "ModifyVariable",
}
MX = "com/llamalad7/mixinextras/injector/"
for n in ("wrapoperation/WrapOperation", "WrapWithCondition", "v2/WrapWithCondition", "ModifyExpressionValue",
          "ModifyReceiver", "ModifyReturnValue", "wrapmethod/WrapMethod"):
    INJ_KINDS[MX + n] = n.rsplit("/", 1)[-1]
REPLACING = {"Overwrite", "Redirect", "ModifyConstant", "ModifyArg", "ModifyArgs", "ModifyVariable", "WrapOperation",
             "WrapWithCondition", "ModifyExpressionValue", "ModifyReceiver", "WrapMethod"}
SINGLE_SLOT = {"Redirect", "ModifyConstant", "Overwrite"}  # two of these on one call/constant cannot both apply

def strs(v):
    if v is None: return []
    if isinstance(v, str): return [v]
    if isinstance(v, list):
        r = []
        for x in v: r += strs(x)
        return r
    return []

def jload(raw):
    t = raw.decode("utf8", "replace") if isinstance(raw, bytes) else raw
    try: return json.loads(t, strict=False)
    except Exception: pass
    t = re.sub(r'(?m)^\s*//.*$', "", t)
    t = re.sub(r",(\s*[\]}])", r"\1", t)
    return json.loads(t, strict=False)

def collect_mixins(zf, label, out, depth=0):
    """out: list of dict(mod, config, cls, targets, prio, pseudo, injectors[], required, plugin, defreq, env)."""
    names = set(zf.namelist())
    try:
        fmj = jload(zf.read("fabric.mod.json"))
    except Exception:
        fmj = None
    if fmj:
        cfgs = []
        for e in fmj.get("mixins") or []:
            if isinstance(e, str): cfgs.append((e, None))
            else: cfgs.append((e.get("config"), e.get("environment")))
        for cfg, env in cfgs:
            if env == "server" or cfg not in names: continue
            try: c = jload(zf.read(cfg))
            except Exception as ex: print("ERR config", label, cfg, ex); continue
            pkg = c.get("package") or ""; refmap = {}
            if c.get("refmap") and c["refmap"] in names:
                try: refmap = json.loads(zf.read(c["refmap"])).get("mappings", {})
                except Exception: pass
            lst = list(c.get("mixins") or []) + list(c.get("client") or [])
            defreq = (c.get("injectors") or {}).get("defaultRequire", 0)
            cprio = c.get("priority", 1000)
            for m in lst:
                if not m: continue
                cn = (pkg + "." + m).replace(".", "/")
                try: cls = parse_class(zf.read(cn + ".class"), want_code=True)
                except KeyError: print("MISSING", label, cn); continue
                except Exception as ex: print("ERR class", label, cn, ex); continue
                mx = [d for t, d in cls["ann"] if t == "Lorg/spongepowered/asm/mixin/Mixin;"]
                if not mx: continue
                d = mx[0]
                targets = [x[1:-1] for x in [v[1] for v in strs_cls(d.get("value"))]] + [t.replace(".", "/") for t in strs(d.get("targets"))]
                pseudo = any(t == "Lorg/spongepowered/asm/mixin/Pseudo;" for t, _ in cls["ann"])
                rm = refmap.get(cn, {})
                rec = dict(mod=label, config=cfg, cls=cn, targets=targets, prio=d.get("priority", cprio),
                           pseudo=pseudo, injectors=[], plugin=bool(c.get("plugin")), defreq=defreq,
                           cfgrequired=c.get("required", True), classobj=cls)
                for m_ in cls["methods"]:
                    for t, ad in m_["ann"]:
                        base = t[1:-1]
                        kind = INJ_KINDS.get(base)
                        if not kind: continue
                        meths = [rm.get(s, s) for s in strs(ad.get("method"))]
                        if kind == "Overwrite": meths = [rm.get(m_["name"], m_["name"])]
                        ats = []
                        for at in (ad.get("at") if isinstance(ad.get("at"), list) else [ad.get("at")] if ad.get("at") else []):
                            if isinstance(at, tuple): ats.append(at[2])
                        atl = []
                        for at in ats:
                            tg = at.get("target")
                            atl.append(dict(value=at.get("value"), target=rm.get(tg, tg) if tg else None,
                                            ordinal=at.get("ordinal"), shift=at.get("shift"), args=at.get("args")))
                        if kind == "ModifyConstant" and ad.get("constant"):
                            for cst in ad["constant"]:
                                if isinstance(cst, tuple): atl.append(dict(value="CONSTANT", target=str(cst[2]), ordinal=cst[2].get("ordinal")))
                        req = ad.get("require", -1); exp = ad.get("expect", 1)
                        if not isinstance(req, int): req = -1
                        eff = req if req >= 0 else defreq
                        rec["injectors"].append(dict(kind=kind, handler=m_["name"], methods=meths, at=atl, require=req,
                                                     eff_require=eff, cancellable=ad.get("cancellable"),
                                                     meth_obj=m_, remap=ad.get("remap")))
                rec["env"] = env
                out.append(rec)
    for n in names:
        if n.startswith("META-INF/jars/") and n.endswith(".jar"):
            try: collect_mixins(zipfile.ZipFile(io.BytesIO(zf.read(n))), label + "!" + os.path.basename(n), out, depth + 1)
            except Exception as ex: print("ERR nested", label, n, ex)

def strs_cls(v):
    if v is None: return []
    if isinstance(v, tuple) and v[0] == "class": return [v]
    if isinstance(v, list):
        r = []
        for x in v: r += strs_cls(x)
        return r
    return []

def split_ref(s):
    """'Lowner;name(desc)ret' | 'name(desc)' | 'name' -> (owner, name, desc)"""
    m = re.match(r"(?:L([^;]+);)?([^(]*)(\(.*)?$", s or "")
    if not m: return (None, s, None)
    return m.group(1), m.group(2), m.group(3)

def meth_match(a, b):
    oa, na, da = split_ref(a); ob, nb, db = split_ref(b)
    if "*" in na or "*" in nb: return na.startswith(nb.rstrip("*")) or nb.startswith(na.rstrip("*"))
    return na == nb and (da is None or db is None or da == db)

BODY_FREE = {"HEAD", "RETURN", "TAIL"}
def verdict(hd, hd_cancel, inj, r, pi, pr, body):
    pk = pi["kind"]
    if pk == "Overwrite": return "CONFLICT (both overwrite; higher priority number wins, other skipped with a warning)"
    ats = pi["at"]
    if hd == "Overwrite":
        if pk in ("Inject",) and ats and all(a_["value"] in BODY_FREE for a_ in ats): return "OK (HEAD/RETURN/TAIL survive an overwrite)"
        if pk in ("Inject", "Redirect", "ModifyArg", "ModifyArgs", "WrapOperation", "WrapWithCondition", "ModifyExpressionValue", "ModifyReceiver", "ModifyReturnValue") and ats:
            lost = []
            for a_ in ats:
                if a_["value"] in BODY_FREE: continue
                tg = a_["target"]
                if tg and a_["value"] in ("INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW", "INVOKE_STRING"):
                    o, n, _ = split_ref(tg)
                    if (o, n) not in body: lost.append(tg)
                elif a_["value"] in ("CONSTANT", "JUMP", "LOAD", "STORE", "RETURN") or not tg:
                    lost.append(a_["value"] + "?")
            if not lost: return "OK (Iroh's body still contains their target)"
            q = "CRASH" if pi["eff_require"] >= 1 else "NOOP"
            if pr["prio"] < r["prio"] and q == "CRASH": return f"NOOP (their mixin applies first, prio {pr['prio']} < {r['prio']}; Iroh's overwrite then discards their change: {tr(lost[0])})"
            if pr["prio"] == r["prio"] and q == "CRASH": q = "CRASH?"
            if any(l.endswith("?") for l in lost) and len(lost) == len([1 for l in lost if l.endswith("?")]): q = "REVIEW"
            return f"{q} (target gone from Iroh body: {tr(lost[0])})"
        if pk in ("ModifyVariable", "ModifyConstant", "ModifyArgs"): return "REVIEW (locals / constants of the overwritten body)"
        if pk == "WrapMethod": return "OK (wraps the whole method)"
        return "REVIEW"
    if hd_cancel:
        if pk == "Inject" and ats and all(a_["value"] == "HEAD" for a_ in ats): return "ORDER (both at HEAD; runs unless Iroh cancels first)"
        return "NOOP-WHEN-CANCELLED (their point comes after Iroh's cancel at HEAD)"
    # Iroh redirect / modify* / wrap*
    mine = {x["target"] for x in inj["at"] if x.get("target")}
    theirs = {x["target"] for x in ats if x.get("target")}
    if hd in ("ModifyConstant",):
        return "REVIEW (same method, constants)" if pk in ("ModifyConstant",) else "OK (different kind)"
    if hd in ("ModifyVariable",):
        return "REVIEW (same method, locals)" if pk == "ModifyVariable" else "OK"
    if mine & theirs:
        if pk in SINGLE_SLOT or hd in SINGLE_SLOT:
            return "CONFLICT (same call, both replace it)" + ("" if inj["eff_require"] == 0 else "; Iroh require>=1 => crash if theirs applies first")
        return "ORDER (inject / wrap at the call Iroh replaces)"
    if not theirs and pk != "Inject": return "REVIEW"
    return "OK (different point in the method)"

def main():
    iroh = []; pack = []; collect_mixins(zipfile.ZipFile(IROH), "iroh", iroh)
    pack += [r for r in iroh if r["mod"] != "iroh"]; iroh = [r for r in iroh if r["mod"] == "iroh"]
    jars = sorted(f for f in os.listdir(MODS) if f.endswith(".jar") and not f.startswith("iroh-"))
    for f in jars:
        if ONLY and ONLY not in f: continue
        try: collect_mixins(zipfile.ZipFile(os.path.join(MODS, f)), f, pack)
        except Exception as ex: print("ERR", f, ex)
    print(f"# jars {len(jars)}, Iroh mixin classes {len(iroh)}, pack mixin classes {len(pack)}, "
          f"pack injectors {sum(len(r['injectors']) for r in pack)}")

    # index pack injectors by target class
    by_cls = collections.defaultdict(list)
    for r in pack:
        for t in r["targets"]:
            by_cls[t].append(r)

    n_hit = 0
    print("\n## A. Iroh replacing handlers vs pack injectors on the same class+method (verdict per pair)\n")
    print("verdicts: OK = their injector survives; CRASH = their target is gone and require>=1; NOOP = gone, require 0;")
    print("          CONFLICT = two single-slot handlers; REVIEW = needs a human; ORDER = survives only if it applies before Iroh's\n")
    verd_count = collections.Counter()
    for r in iroh:
        for inj in r["injectors"]:
            hd = inj["kind"]
            hd_cancel = hd == "Inject" and inj["cancellable"] and any(a_["value"] == "HEAD" for a_ in inj["at"])
            if hd not in REPLACING and not hd_cancel: continue
            body = None
            if hd == "Overwrite":
                # calls to @Shadow / merged methods carry the mixin class as owner; after the merge they are calls on the target
                body = {((t0 if o == r["cls"] else o), n) for _, o, n, _ in invokes(r["classobj"], inj["meth_obj"]) for t0 in r["targets"][:1]}
            for t in r["targets"]:
                for pr in by_cls.get(t, []):
                    for pi in pr["injectors"]:
                        for im in inj["methods"]:
                            if not any(meth_match(im, pm) for pm in pi["methods"]): continue
                            n_hit += 1
                            v = verdict(hd, hd_cancel, inj, r, pi, pr, body)
                            verd_count[v.split()[0]] += 1
                            atx = "; ".join(f"{a_['value']}:{tr(a_['target'])}" + (f"#{a_['ordinal']}" if a_.get("ordinal") not in (None, -1) else "") for a_ in pi["at"]) or "-"
                            print(f"{v}\n  iroh: {r['cls'].split('/')[-1]}.{inj['handler']} [{hd}{'(cancel@HEAD)' if hd_cancel else ''}] prio={r['prio']} -> {tr(t)} {tr(im)}"
                                  f"\n  pack: {pr['mod']} :: {pr['cls']}.{pi['handler']} [{pi['kind']}] at={atx} require={pi['require']}(eff {pi['eff_require']}) prio={pr['prio']} pseudo={pr['pseudo']} plugin={pr['plugin']}"
                                  + (f"\n  iroh body calls: " + ",".join(sorted(f"{o.split('/')[-1]}.{n}" for o, n in body if not o.startswith("java/")))[:240] if body is not None else ""))
    print(f"\n# A pairs: {n_hit}  verdicts: {dict(verd_count)}")

    print("\n## B. single-slot collisions: Iroh Redirect/ModifyConstant at a call another mod also redirects/modifies\n")
    nb = 0
    for r in iroh:
        for inj in r["injectors"]:
            if inj["kind"] not in SINGLE_SLOT or inj["kind"] == "Overwrite": continue
            for t in r["targets"]:
                for pr in by_cls.get(t, []):
                    for pi in pr["injectors"]:
                        if pi["kind"] not in SINGLE_SLOT or pi["kind"] == "Overwrite" and False: continue
                        if not any(meth_match(a_, b_) for a_ in inj["methods"] for b_ in pi["methods"]): continue
                        sa = {(x["target"] if x["value"] != "CONSTANT" else x["target"]) for x in inj["at"]}
                        sb = {x["target"] for x in pi["at"]}
                        if sa & sb:
                            nb += 1
                            print(f"{r['cls'].split('/')[-1]}.{inj['handler']} [{inj['kind']} require={inj['eff_require']} prio={r['prio']}] vs "
                                  f"{pr['mod']} :: {pr['cls']}.{pi['handler']} [{pi['kind']} require={pi['eff_require']} prio={pr['prio']}]  on {tr(sorted(sa & sb)[0])}")
    print(f"\n# B hits: {nb}")

    # C. also: all Iroh INVOKE-target injections (Inject/Redirect/...) that a pack mixin ALSO targets (informational)
    # D. pack methods that @Overwrite something Iroh also touches
    print("\n## D. pack @Overwrite or @Redirect on a method Iroh also overwrites/redirects/injects (reverse direction)\n")
    iroh_idx = collections.defaultdict(list)
    for r in iroh:
        for t in r["targets"]:
            iroh_idx[t].append(r)
    nd = 0
    for pr in pack:
        for t in pr["targets"]:
            for ir in iroh_idx.get(t, []):
                for pi in pr["injectors"]:
                    if pi["kind"] not in ("Overwrite",): continue
                    for ii in ir["injectors"]:
                        if any(meth_match(a_, b_) for a_ in ii["methods"] for b_ in pi["methods"]):
                            nd += 1
                            print(f"{pr['mod']} :: {pr['cls']}.{pi['handler']} [Overwrite prio={pr['prio']}] vs Iroh {ir['cls'].split('/')[-1]}.{ii['handler']} [{ii['kind']}] {tr(t)} {tr(pi['methods'][0])}")
    print(f"\n# D hits: {nd}")

    if GL:
        gl_scan(jars)

def gl_scan(jars):
    print("\n## C. direct org/lwjgl/opengl calls per jar (class, kind, function list)\n")
    for f in jars:
        if ONLY and ONLY not in f: continue
        try: z = zipfile.ZipFile(os.path.join(MODS, f))
        except Exception: continue
        glscan_zip(z, f)

def glscan_zip(z, label):
    for n in z.namelist():
        if n.startswith("META-INF/jars/") and n.endswith(".jar"):
            try: glscan_zip(zipfile.ZipFile(io.BytesIO(z.read(n))), label + "!" + os.path.basename(n))
            except Exception: pass
        elif n.endswith(".class"):
            b = z.read(n)
            if b"org/lwjgl/opengl/" not in b: continue
            try: cls = parse_class(b, want_code=True)
            except Exception: continue
            calls = collections.defaultdict(set)
            for m in cls["methods"]:
                for op, o, nm, d in invokes(cls, m):
                    if o.startswith("org/lwjgl/opengl/"): calls[m["name"]].add(f"{o.split('/')[-1]}.{nm}")
            if calls:
                print(f"{label} :: {cls['this']} :: " + " | ".join(f"{k}: {','.join(sorted(v))}" for k, v in sorted(calls.items())))

main()
