"""Compact Wiktionary (kaikki.org extract) → word meanings + inflected forms, for the app.
Output lines:  E<TAB>word<TAB>pos<TAB>meaning 1 | meaning 2…<TAB>romanization
               F<TAB>form<TAB>word            (an inflected form → its dictionary word)
"""
import json, sys, gzip, re, unicodedata
src, out = sys.argv[1], sys.argv[2]
SKIP = {"romanization", "table-tags", "inflection-template", "class"}
entries, forms = {}, {}
def clean(g): return re.sub(r"\s+", " ", g).strip()
with open(src, encoding="utf-8") as f:
    for line in f:
        d = json.loads(line)
        w = unicodedata.normalize("NFC", d.get("word", "")).strip()
        if not w or " " in w and len(w) > 40: continue
        glosses = []
        for s in d.get("senses", []):
            if "form-of" in s.get("tags", []) or "alt-of" in s:   # "plural of X": keep as a form instead
                for t in s.get("form_of", []) + s.get("alt_of", []):
                    if t.get("word"): forms.setdefault(w, t["word"])
                continue
            g = s.get("glosses") or s.get("raw_glosses")
            if g: glosses.append(clean(g[-1])[:140])
        rom = next((x["form"] for x in d.get("forms", []) if "romanization" in x.get("tags", [])), "")
        if glosses:
            e = entries.setdefault(w, {"pos": [], "gl": [], "rom": rom})
            if d.get("pos") and d["pos"] not in e["pos"]: e["pos"].append(d["pos"])
            for g in glosses:
                if g not in e["gl"] and len(e["gl"]) < 6: e["gl"].append(g)
        for x in d.get("forms", []):
            fm = unicodedata.normalize("NFC", x.get("form", "")).strip()
            if not fm or fm == w or SKIP & set(x.get("tags", [])) or fm == "-" or len(fm) > 40: continue
            forms.setdefault(fm, w)
with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as o:
    for w, e in sorted(entries.items()):
        o.write(f"E\t{w}\t{'/'.join(e['pos'])}\t{' | '.join(e['gl'])}\t{e['rom']}\n")
    for fm, w in sorted(forms.items()):
        if fm not in entries and w in entries: o.write(f"F\t{fm}\t{w}\n")
print(len(entries), "words,", sum(1 for fm, w in forms.items() if fm not in entries and w in entries), "forms")
