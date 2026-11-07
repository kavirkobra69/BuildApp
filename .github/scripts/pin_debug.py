import json, os, re, struct, sys, urllib.request
UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

def get(u, rng=None, method="GET"):
    h = {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"}
    if rng:
        h["Range"] = rng
    r = urllib.request.Request(u, headers=h, method=method)
    with urllib.request.urlopen(r, timeout=40) as x:
        return x.status, dict(x.headers), x.read() if method == "GET" else b"", x.geturl()

def dims(b):
    if b[:8] == b"\x89PNG\r\n\x1a\n":
        return "png", struct.unpack(">II", b[16:24])
    if b[:3] == b"GIF":
        return "gif", struct.unpack("<HH", b[6:10])
    if b[:3] == b"\xff\xd8\xff":
        i = 2
        while i < len(b) - 9:
            if b[i] != 0xFF:
                i += 1
                continue
            m = b[i + 1]
            if m in (0xC0, 0xC1, 0xC2):
                h, w = struct.unpack(">HH", b[i + 5:i + 9])
                return "jpg", (w, h)
            i += 2 + struct.unpack(">H", b[i + 2:i + 4])[0]
    if b[:4] == b"RIFF":
        return "webp", None
    return "?", None

def gif_frames(b):
    return b.count(b"\x00\x21\xf9\x04")

def probe(u):
    try:
        s, h, b, final = get(u)
        k, d = dims(b)
        extra = f" frames={gif_frames(b)}" if k == "gif" else ""
        print(f"   GET {u}\n      -> {s} ct={h.get('Content-Type')} len={len(b)} kind={k} dims={d}{extra}")
        return k, d, len(b)
    except Exception as e:
        print(f"   GET {u}\n      -> ERR {e}")
        return None

def analyze(pin):
    print("=" * 70)
    print("PIN", pin)
    try:
        s, h, b, final = get(pin)
    except Exception as e:
        print("page ERR", e)
        return
    html = b.decode("utf-8", "replace").replace("\\/", "/")
    print("final", final, "len", len(html))
    og = re.findall(r'<meta[^>]+property=["\']og:(image|video|title)["\'][^>]+content=["\']([^"\']+)', html)
    og += [(k, v) for v, k in re.findall(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:(image|video|title)["\']', html)]
    print("og:", og[:6])
    print("markers:", {k: (k in html) for k in ['"video_list"', '"V_720P"', "og:video", '"orig"', '"images"', '"story_pin_data"', "animated", ".gif"]})
    for m in list(re.finditer(r'"images"\s*:\s*\{', html))[:3]:
        print("images-block:", html[m.start():m.start() + 700].replace("\n", " "))
    sizes = {}
    for m in re.finditer(r'https://i\.pinimg\.com/([A-Za-z0-9_]+)/[A-Za-z0-9/_\-]+\.(jpg|jpeg|png|gif|webp)', html):
        sizes.setdefault(m.group(1), set()).add(m.group(0))
    print("pinimg size dirs:", {k: len(v) for k, v in sizes.items()})
    vids = set(re.findall(r'https://v\d*\.pinimg\.com[^"\\\s]+', html))
    print("video urls:", list(vids)[:6])
    cands = []
    for m in re.finditer(r'"orig"\s*:\s*\{([^{}]*)\}', html):
        u = re.search(r'"url"\s*:\s*"([^"]+)"', m.group(1))
        w = re.search(r'"width"\s*:\s*(\d+)', m.group(1))
        hh = re.search(r'"height"\s*:\s*(\d+)', m.group(1))
        if u:
            print("orig entry:", u.group(1), w and w.group(1), hh and hh.group(1))
            cands.append(u.group(1))
    for d in ("originals", "1200x", "736x", "564x", "474x", "236x"):
        for u in list(sizes.get(d, []))[:1]:
            cands.append(u)
    seen = set()
    for c in cands[:8]:
        if c in seen:
            continue
        seen.add(c)
        probe(c)
        up = re.sub(r"pinimg\.com/\d+x/", "pinimg.com/originals/", c)
        if up != c and up not in seen:
            seen.add(up)
            probe(up)
        base = up.rsplit(".", 1)[0]
        for e in ("gif", "png", "jpg"):
            u2 = f"{base}.{e}"
            if u2 not in seen:
                seen.add(u2)
                probe(u2)

pins = sys.argv[1:]
if not pins:
    for q in ("wallpaper", "funny gif", "anime gif", "pixel art gif"):
        try:
            s, h, b, final = get("https://www.pinterest.com/search/pins/?q=" + q.replace(" ", "%20"))
            ids = re.findall(r'/pin/(\d{8,})', b.decode("utf-8", "replace"))
            print("search", q, s, "pins found:", len(set(ids)))
            pins += ["https://www.pinterest.com/pin/%s/" % i for i in list(dict.fromkeys(ids))[:3]]
        except Exception as e:
            print("search ERR", q, e)
for p in pins[:12]:
    analyze(p)
