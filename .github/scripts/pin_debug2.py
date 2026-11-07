import json, re, sys, urllib.request
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
for pin in sys.argv[1:]:
    print("=" * 60, pin)
    r = urllib.request.Request(pin, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    with urllib.request.urlopen(r, timeout=40) as x:
        html = x.read().decode("utf-8", "replace")
        final = x.geturl()
    pid = re.search(r"/pin/(?:[^/]*-)?(\d+)", final).group(1)
    tags = re.findall(r'<script([^>]*)type="application/(?:ld\+)?json"[^>]*>(.*?)</script>', html, re.S)
    for a, t in tags:
        if "snippet" in a:
            print("LD", a.strip()[:60], t[:900])
    def walk(o):
        if isinstance(o, dict):
            if pid in (str(o.get("entityId")), str(o.get("id"))) and any(("mage" in k or "ideo" in k) for k in o):
                yield o
            for v in o.values():
                yield from walk(v)
        elif isinstance(o, list):
            for v in o:
                yield from walk(v)
    shown = 0
    for a, t in tags:
        try:
            d = json.loads(t)
        except Exception:
            continue
        for o in walk(d):
            ks = [k for k in o if "mage" in k or "ideo" in k or k in ("type", "__typename", "isVideo", "isGif", "storyPinData")]
            print("OBJ keys(image/video):", ks)
            for k in ks:
                v = o[k]
                s = json.dumps(v)
                print("  ", k, "=", s[:600])
            shown += 1
            if shown >= 2:
                break
