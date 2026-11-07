import json, re, sys, urllib.request
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
for pin in sys.argv[1:]:
    print("=" * 60, pin)
    r = urllib.request.Request(pin, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    with urllib.request.urlopen(r, timeout=40) as x:
        html = x.read().decode("utf-8", "replace")
        final = x.geturl()
    pid = re.search(r"/pin/(?:[^/]*-)?(\d+)", final).group(1)
    print("final", final, "pid", pid, "occurrences", html.count(pid))
    for m in re.finditer(r"<script([^>]*)>", html):
        attrs = m.group(1)
        if "json" in attrs or "__PWS" in attrs or "id=" in attrs:
            end = html.index("</script>", m.end())
            print("script", attrs[:120], "len", end - m.end())
    h2 = html.replace("\\/", "/")
    for key in ['"images"', "v1.pinimg.com/videos", "hevcMp4V2", '"video_list"', '"videos"', '"orig"']:
        ms = [m.start() for m in re.finditer(re.escape(key), h2)]
        print("KEY", key, "count", len(ms))
        for st in ms[:2]:
            print("   ctx:", h2[max(0, st - 120):st + 380].replace("\n", " "))
    # find pin dict via json
    tags = re.findall(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', html, re.S)
    print("json script tags:", len(tags))
    def walk(o, depth=0):
        if isinstance(o, dict):
            if str(o.get("id")) == pid and ("images" in o or "videos" in o or "story_pin_data" in o):
                yield o
            for v in o.values():
                yield from walk(v, depth + 1)
        elif isinstance(o, list):
            for v in o:
                yield from walk(v, depth + 1)
    for t in tags:
        try:
            d = json.loads(t)
        except Exception as e:
            print("json err", e)
            continue
        for o in walk(d):
            print("PIN OBJ keys:", sorted(o.keys())[:60])
            im = o.get("images")
            if isinstance(im, dict):
                print(" images:", {k: (v.get("width"), v.get("height"), v.get("url")[-40:]) for k, v in im.items() if isinstance(v, dict)})
            vd = o.get("videos")
            if vd:
                print(" videos:", json.dumps(vd)[:900])
            sp = o.get("story_pin_data")
            if sp:
                print(" story:", json.dumps(sp)[:500])
            print(" type:", o.get("type"), "is_video", o.get("is_video"), "is_gif?", o.get("is_gif"), "ext", o.get("extra"))
            break
