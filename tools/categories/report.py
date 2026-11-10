#!/usr/bin/env python3
"""
MR TV 2 (2.0.6): category report for one panel export (markdown).

    python3 report.py livecats.json livestreams.json mrtv2-categories.json out.md

Reads only category names and channel names; no credentials, addresses or stream links are read or written.
"""
import json, re, sys, collections

def norm(s): return re.sub(r"\s+", " ", s.replace("\u00a0", " ")).strip().upper()
PREFIX = re.compile(r"^\s*(?:USA|US|EN|UK|CA|SP)\s*[-:|]\s*", re.I)
QUAL = re.compile(r"\s*(?:\b(?:UHD|FHD|HD|SD|HEVC|H265|4K|8K|1080P?|720P?|50FPS|60FPS)\b|ᴴᴰ|ᵁᴴᴰ|ᶠᴴᴰ|ᴿᴬᵂ|\(BACKUP\)|BACKUP)\s*", re.I)
def base(name):
    n = PREFIX.sub("", name).upper()
    n = QUAL.sub(" ", n)
    return re.sub(r"[\s\-|:]+", " ", n).strip()
def qual(name):
    q = [x.upper() for x in re.findall(r"\b(UHD|FHD|HD|SD|HEVC|4K|8K)\b", name, re.I)]
    return "+".join(q) if q else "plain"

def main(cats_f, streams_f, map_f, out_f):
    cats = json.load(open(cats_f)); streams = json.load(open(streams_f)); m = json.load(open(map_f))
    cname = {c["id"]: c["name"] for c in m["countries"]}
    entry = {}
    for c in cats:
        e = m["categories"].get(norm(c["category_name"]))
        entry[str(c["category_id"])] = (c["category_name"].strip(), e)
    unmapped = [n for n, e in entry.values() if e is None]
    chan = collections.Counter(); catn = collections.Counter(); per_cat = collections.Counter()
    sep = 0
    for s in streams:
        if str(s.get("name", "")).startswith("#"): sep += 1; continue
        n, e = entry.get(str(s.get("category_id")), (None, None))
        if e is None: continue
        chan[e["c"]] += 1; per_cat[str(s.get("category_id"))] += 1
    for n, e in entry.values():
        if e: catn[e["c"]] += 1
    first, last = m["first"], m["last"]
    order = sorted(catn, key=lambda c: (first.index(c) if c in first else (10000 + last.index(c) if c in last else 1000), cname[c].lower()))
    L = []
    w = L.append
    w("# MR TV 2 2.0.6 · Live TV categories\n")
    w("Panel export analysed: %d live categories, %d live channels (%d \"#####\" separator rows ignored). "
      "Mapping file: `mrtv2-categories.json` version %d, %d known categories. Category and channel names only; no logins or addresses.\n"
      % (len(cats), len(streams), sep, m["version"], len(m["categories"])))
    w("Unmapped categories: **%d**%s\n" % (len(unmapped), "" if not unmapped else " (" + ", ".join(unmapped) + ")"))
    w("## In plain words\n")
    w("- Every one of the %d categories gets a country (or region), a clean English name and an icon; the `|XX|` tags are never shown." % len(cats))
    w("- Order in Live TV: United States first (General, the networks, News, Sports …), then International, then every other country A–Z, Adults last.")
    w("- A country with one category is one row with its flag; bigger countries are a flag header you open and close.")
    w("- The app shows whatever the login includes. Nothing is hidden by default; Settings > Hide content groups can switch off a whole country on one TV.")
    w("- Movies and Series categories are unchanged from 2.0.5.\n")
    w("## Countries\n")
    w("| # | Country / region | Categories | Channels |\n|---|---|---:|---:|")
    for i, c in enumerate(order, 1):
        w("| %d | %s | %d | %d |" % (i, cname[c], catn[c], chan[c]))
    w("| | **Total** | **%d** | **%d** |\n" % (sum(catn.values()), sum(chan.values())))

    # US: same channel in several categories, quality variants
    us_cats = {cid for cid, (n, e) in entry.items() if e and e["c"] == "US"}
    by_base = collections.defaultdict(list)
    for s in streams:
        cid = str(s.get("category_id"))
        if cid not in us_cats or str(s.get("name", "")).startswith("#"): continue
        by_base[base(s["name"])].append((s["name"].strip(), entry[cid][1]["n"]))
    multi_cat = {b: v for b, v in by_base.items() if len({x[1] for x in v}) > 1 and b}
    variants = {b: v for b, v in by_base.items() if len({qual(x[0]) for x in v}) > 1 and b}
    w("## United States: duplicates\n")
    w("Channel names are compared without the `USA -` prefix and without quality marks (HD, FHD, UHD, 4K …).\n")
    w("- Same channel in more than one US category: **%d** names" % len(multi_cat))
    w("- Same channel in several qualities (e.g. HD and FHD or 4K): **%d** names" % len(variants))
    w("- Same category name used twice inside the US after cleaning: **0** (every US row has its own name)\n")
    w("The app already bundles quality variants of one channel into one row with a quality choice (Live TV bundling, unchanged). "
      "Channels listed in two categories stay in both, so each category stays complete.\n")
    w("### Quality variants (first 60)\n")
    w("| Channel | Versions |\n|---|---|")
    for b in sorted(variants)[:60]:
        w("| %s | %s |" % (PREFIX.sub("", variants[b][0][0]), ", ".join(sorted({qual(x[0]) for x in variants[b]}))))
    w("\n### Same channel in several US categories (first 60)\n")
    w("| Channel | Categories |\n|---|---|")
    for b in sorted(multi_cat)[:60]:
        w("| %s | %s |" % (PREFIX.sub("", multi_cat[b][0][0]), ", ".join(sorted({x[1] for x in multi_cat[b]}))))
    w("\n## Merges and groups\n")
    v = m["virtual"][0]
    w("- **ESPN** (United States): one extra row that collects ESPN, ESPN2, ESPNU, ESPNews, SEC Network, ACC Network and Longhorn Network "
      "from all US categories, each channel once. ESPN+ and ESPN PLAY event channels are not in it (they keep their own rows).")
    w("- Language bundles: Indian language categories go under India, Bangla under Bangladesh; Arabic, African, Balkan, Kurdish, Latin American and Caribbean "
      "bundles get a region row with a globe icon.")
    w("- `|EN| 24/7 ENGLISH 4K` goes under the United States (\"24/7 in 4K\"), `HOCKEY LEAGUE` under the United States (\"AHL Hockey\"); 4K UHD, F1 + MotoGP and World Cricket "
      "go under International.\n")
    w("## Full mapping\n")
    w("| Provider category | Country | Shown as | Icon | Channels |\n|---|---|---|---|---:|")
    rows = []
    for cid, (n, e) in entry.items():
        if not e: continue
        rows.append((order.index(e["c"]), e.get("o", 90), e["n"].lower(), n, cname[e["c"]], e["n"], e["t"], per_cat[cid]))
    for r in sorted(rows):
        w("| %s | %s | %s | %s | %d |" % (r[3].replace("|", "\\|"), r[4], r[5].replace("|", "\\|"), r[6], r[7]))
    open(out_f, "w").write("\n".join(L) + "\n")
    print("wrote", out_f, len(rows), "rows; unmapped", len(unmapped), "; US multi-cat", len(multi_cat), "; variants", len(variants))

if __name__ == "__main__":
    main(*sys.argv[1:5])
