import json
import os
import re
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

URL = os.environ["URL"].strip()
QUALITY = os.environ.get("QUALITY", "720").strip()
AUDIO = os.environ.get("AUDIO", "false") == "true" or QUALITY == "audio"
WARP = os.environ.get("PROXY", "")
JOB = os.environ.get("JOB", "")
REPO = os.environ.get("GITHUB_REPOSITORY", "")
TOKEN = os.environ.get("GH_TOKEN", "")
OUT = Path("out")
OUT.mkdir(exist_ok=True)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
MEDIA = {"mp4", "mkv", "webm", "mov", "mp3", "m4a", "opus", "ogg", "wav", "flac", "gif", "png", "jpg", "jpeg", "webp"}

STATE = {"phase": "download", "title": "", "thumb": "", "percent": -1, "downloaded": 0, "total": 0, "speed": 0, "eta": -1, "index": 1, "count": 1}
_last = 0.0
_rel = None


def gh(method, path, payload=None):
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={
            "Authorization": "Bearer " + TOKEN,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "kavirdl",
        },
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode() or "{}")


def report(force=False, **kw):
    global _last, _rel
    STATE.update(kw)
    now = time.time()
    if not force and now - _last < 2:
        return
    _last = now
    if not (TOKEN and REPO and JOB):
        return
    try:
        if _rel is None:
            _rel = gh("GET", f"/repos/{REPO}/releases/tags/dl-{JOB}")["id"]
        gh("PATCH", f"/repos/{REPO}/releases/{_rel}", {"body": json.dumps(STATE, ensure_ascii=False)})
    except Exception as e:
        print("report failed:", e)


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace"), r.geturl()


def save_file(url, path):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r, open(path, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        got = 0
        t0 = time.time()
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)
            got += len(chunk)
            sp = got / max(time.time() - t0, 0.001)
            report(percent=(got / total) if total else -1, downloaded=got, total=total, speed=int(sp), eta=int((total - got) / sp) if total and sp else -1)


def safe(s):
    return re.sub(r'[\\/:*?"<>|\r\n%]', "_", s).strip()[:100] or "file"


def video_format():
    if QUALITY.isdigit():
        h = QUALITY
        return (
            f"bv*[height<={h}][ext=mp4]+ba[ext=m4a]/b[height<={h}][ext=mp4]/"
            f"bv*[height<={h}]+ba/b[height<={h}]/b"
        )
    return "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"


def num(x):
    try:
        return float(x)
    except Exception:
        return 0.0


def probe(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr[-800:])
        return None
    try:
        info = json.loads(r.stdout)
    except Exception:
        return None
    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            return None
        info = entries[0]
    return info


def run_download(cmd, expected_total, track_index, track_count):
    cmd = cmd + [
        "--newline",
        "--progress-template",
        "download:PROG|%(progress.filename)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s",
    ]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    seen = {}
    totals = {}
    t0 = time.time()
    for line in p.stdout:
        sys.stdout.write(line)
        if not line.startswith("PROG|"):
            continue
        try:
            fname, dl, tb, te, sp = line.strip()[5:].rsplit("|", 4)
        except ValueError:
            continue
        seen[fname] = max(seen.get(fname, 0), int(num(dl)))
        st = int(num(tb)) or int(num(te))
        if st:
            totals[fname] = max(totals.get(fname, 0), st)
        done = sum(seen.values())
        total = max(expected_total, sum(totals.values()))
        speed = num(sp)
        if speed <= 0:
            speed = done / max(time.time() - t0, 0.001)
        frac = (done / total) if total else -1
        if frac > 1:
            frac = 1
        if track_count > 1:
            overall = ((track_index + (frac if frac >= 0 else 0)) / track_count)
            report(percent=overall, downloaded=0, total=0, speed=int(speed), eta=-1, index=track_index + 1, count=track_count)
        else:
            eta = int((total - done) / speed) if total and speed > 0 else -1
            report(percent=frac, downloaded=done, total=total, speed=int(speed), eta=eta)
    return p.wait()


def ytdlp(target, name=None, audio=False, search=False, track_index=0, track_count=1):
    template = str(OUT / ((name if name else "%(title).80s") + ".%(ext)s"))
    common = ["yt-dlp", "--no-warnings", "--no-mtime", "--retries", "10", "--fragment-retries", "10"]
    common += ["--playlist-items", "1"] if search else ["--no-playlist"]
    fmt = ["-f", "ba/b"] if audio else ["-f", video_format()]
    yt = target.startswith("ytsearch") or "youtu" in target or "instagram" in target
    if yt and WARP:
        proxies = [WARP, ""]
    elif WARP:
        proxies = ["", WARP]
    else:
        proxies = [""]
    extras = (
        [
            [],
            ["--extractor-args", "youtube:player_client=android_vr"],
            ["--extractor-args", "youtube:player_client=tv_simply,mweb"],
            ["--extractor-args", "youtube:player_client=android,ios"],
            ["--js-runtimes", "node"],
        ]
        if yt
        else [[]]
    )
    for p in proxies:
        for extra in extras:
            px = ["--proxy", p] if p else []
            info = probe(common + fmt + px + extra + ["-J", target])
            if info is None:
                continue
            formats = info.get("requested_formats") or [info]
            sizes = [f.get("filesize") or f.get("filesize_approx") or 0 for f in formats]
            expected = sum(sizes) if all(sizes) else 0
            title = info.get("title") or ""
            if track_count == 1:
                report(force=True, title=title, thumb=info.get("thumbnail") or "", percent=0 if expected else -1, total=expected, downloaded=0, speed=0, eta=-1)
            dl = common + ["-o", template] + fmt + px + extra
            if audio:
                dl += ["-x", "--audio-format", "mp3", "--audio-quality", "0", "--embed-metadata"]
            else:
                dl += ["--merge-output-format", "mp4"]
            if run_download(dl + [target], expected, track_index, track_count) == 0:
                return True
    return False


def spotify(url):
    if "open.spotify.com" not in url:
        _, url = fetch(url)
    m = re.search(r"open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist)/([A-Za-z0-9]+)", url)
    if not m:
        sys.exit("لینک اسپاتیفای معتبر نیست")
    kind, sid = m.group(1), m.group(2)
    html, _ = fetch(f"https://open.spotify.com/embed/{kind}/{sid}")
    tracks = []
    mm = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S)
    entity = None
    if mm:
        try:
            entity = json.loads(mm.group(1))["props"]["pageProps"]["state"]["data"]["entity"]
        except Exception:
            entity = None

    def one(o):
        title = o.get("name") or o.get("title") or ""
        artists = o.get("artists")
        if isinstance(artists, list):
            artist = ", ".join(a.get("name", "") for a in artists if isinstance(a, dict))
        else:
            artist = o.get("subtitle", "") or ""
        return title.strip(), artist.strip()

    if entity:
        if kind == "track":
            tracks = [one(entity)]
        else:
            tracks = [one(t) for t in entity.get("trackList", []) if isinstance(t, dict)]
    tracks = [t for t in tracks if t[0]][:100]
    if not tracks:
        oe, _ = fetch("https://open.spotify.com/oembed?url=" + urllib.parse.quote(f"https://open.spotify.com/{kind}/{sid}"))
        title = json.loads(oe).get("title", "")
        if not title:
            sys.exit("اطلاعات آهنگ از اسپاتیفای گرفته نشد")
        tracks = [(title, "")]
    n = len(tracks)
    for i, (title, artist) in enumerate(tracks):
        q = f"{artist} - {title}" if artist else title
        report(force=True, title=q, thumb="", percent=i / n, index=i + 1, count=n, downloaded=0, total=0, speed=0, eta=-1)
        ytdlp(f"ytsearch1:{q} audio", name=safe(q), audio=True, search=True, track_index=i, track_count=n)


def _pin_probe(u):
    try:
        req = urllib.request.Request(u, headers={"User-Agent": UA, "Range": "bytes=0-15"})
        with urllib.request.urlopen(req, timeout=20) as r:
            head = r.read(16)
            return r.status in (200, 206) and (r.headers.get("Content-Type", "").startswith("image/") or head[:3] == b"GIF")
    except Exception:
        return False


def _magic_ext(path):
    h = open(path, "rb").read(16)
    if h[:3] == b"GIF":
        return "gif"
    if h[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if h[:3] == b"\xff\xd8\xff":
        return "jpg"
    if h[:4] == b"RIFF" and h[8:12] == b"WEBP":
        return "webp"
    return ""


def _img_info(path):
    b = open(path, "rb").read()
    if b[:3] == b"GIF":
        w, h = struct.unpack("<HH", b[6:10])
        return "gif", w, h, b.count(b"\x00\x21\xf9\x04")
    if b[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = struct.unpack(">II", b[16:24])
        return "png", w, h, 1
    if b[:3] == b"\xff\xd8\xff":
        i = 2
        while i < len(b) - 9:
            if b[i] != 0xFF:
                i += 1
                continue
            m = b[i + 1]
            if m in (0xC0, 0xC1, 0xC2):
                h, w = struct.unpack(">HH", b[i + 5:i + 9])
                return "jpg", w, h, 1
            i += 2 + struct.unpack(">H", b[i + 2:i + 4])[0]
        return "jpg", 0, 0, 1
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP":
        t = b[12:16]
        if t == b"VP8X":
            return "webp", 1 + int.from_bytes(b[24:27], "little"), 1 + int.from_bytes(b[27:30], "little"), 1
        if t == b"VP8 ":
            w, h = struct.unpack("<HH", b[26:30])
            return "webp", w & 0x3FFF, h & 0x3FFF, 1
        if t == b"VP8L":
            v = int.from_bytes(b[21:25], "little")
            return "webp", (v & 0x3FFF) + 1, ((v >> 14) & 0x3FFF) + 1, 1
        return "webp", 0, 0, 1
    return "", 0, 0, 0


def _pin_scripts(html):
    out = []
    for m in re.finditer(r'<script[^>]*type="application/(?:ld\+)?json"[^>]*>(.*?)</script>', html, re.S):
        try:
            out.append(json.loads(m.group(1)))
        except Exception:
            pass
    return out


def _pin_object(objs, pid):
    best = [None]

    def walk(o):
        if isinstance(o, dict):
            if pid in (str(o.get("entityId")), str(o.get("id"))) and ("images" in o or "videos" in o):
                if best[0] is None or len(o) > len(best[0]):
                    best[0] = o
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    for o in objs:
        walk(o)
    return best[0]


def _pin_get(url, name):
    tmp = OUT / name
    save_file(url, tmp)
    return tmp


def pinterest(url):
    sys.setrecursionlimit(10000)
    kind = QUALITY if QUALITY in ("image", "gif") else "auto"
    raw, final = fetch(url)
    m = re.search(r"/pin/(?:[^/]*-)?(\d+)", final)
    pid = m.group(1) if m else ""
    objs = _pin_scripts(raw)
    pin = _pin_object(objs, pid) if pid else None
    ld_img = ld_vid = None
    for o in objs:
        if isinstance(o, dict):
            t = o.get("@type")
            if t == "SocialMediaPosting":
                i = o.get("image")
                ld_img = i if isinstance(i, str) else (i[0] if isinstance(i, list) and i and isinstance(i[0], str) else None)
            elif t == "VideoObject":
                ld_vid = o.get("contentUrl")
    html = raw.replace("\\/", "/")
    ogm = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)', html) or re.search(
        r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:image["\']', html)
    og = ogm.group(1).replace("&amp;", "&") if ogm else None
    tm = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)', html)
    title = (tm.group(1).split(" | ")[0] if tm else "Pinterest").replace("&amp;", "&").replace("&#x27;", "'")[:100]

    images, vids = {}, []
    if pin:
        im = pin.get("images")
        for k, v in (im.items() if isinstance(im, dict) else []):
            if isinstance(v, dict) and v.get("url"):
                images[k] = (int(v.get("width") or 0), int(v.get("height") or 0), v["url"])
        vd = pin.get("videos")
        vl = vd.get("video_list") if isinstance(vd, dict) else None
        for k, v in (vl.items() if isinstance(vl, dict) else []):
            u = v.get("url", "") if isinstance(v, dict) else ""
            if u.split("?")[0].endswith(".mp4"):
                vids.append((int(v.get("height") or 0), int(v.get("width") or 0), u))
    if ld_vid and ld_vid.endswith(".mp4") and not any(u == ld_vid for _, _, u in vids):
        vids.append((0, 0, ld_vid))
    vids.sort(reverse=True)
    has_video = bool(vids)
    thumb = (images.get("736x") or images.get("474x") or (0, 0, og or ""))[2]
    report(force=True, title=title, thumb=thumb, percent=-1)
    print(f"pin={pid} video={has_video} sizes={sorted(images)} ld={ld_img} og={og}", flush=True)

    def video():
        if kind == "auto" and ytdlp(url):
            return
        if not vids:
            sys.exit("ویدیوی پینترست پیدا نشد")
        out = _pin_get(vids[0][2], f"pinterest_{pid or 'video'}.mp4")
        if out.stat().st_size < 1000 or out.read_bytes()[4:8] != b"ftyp":
            out.unlink()
            sys.exit("فایل ویدیوی دریافتی معتبر نبود")

    if has_video and kind == "auto":
        video()
        return
    if has_video and kind == "image":
        sys.exit("این پین ویدیو یا انیمیشنه و عکسش فقط تصویر پیش‌نمایشه. برای فایل متحرک دکمه GIF رو بزن")

    seeds = [images[k][2] for k in images] + [u for u in (ld_img, og) if u]
    bases, order = [], []
    for u in seeds:
        base = re.sub(r"pinimg\.com/[^/]+/", "pinimg.com/originals/", u).rsplit(".", 1)[0]
        if "/originals/" in base and base not in bases:
            bases.append(base)
    cands = []
    for u in ([images["orig"][2]] if "orig" in images else []) + ([ld_img] if ld_img else []):
        if u not in cands:
            cands.append(u)
    exts = ["gif"] if kind == "gif" else ["jpg", "png", "webp", "gif", "jpeg"]
    pref = [u.rsplit(".", 1)[-1].lower() for u in cands]
    for base in bases[:1] if pid else bases:
        for e in dict.fromkeys(pref + exts):
            if kind == "gif" and e != "gif":
                continue
            u = f"{base}.{e}"
            if u not in cands:
                cands.append(u)
    if kind == "gif":
        cands = [u for u in cands if u.lower().split("?")[0].endswith(".gif")]

    chosen = None
    for c in cands:
        if _pin_probe(c):
            chosen = c
            break
    if kind == "gif":
        if chosen:
            tmp = _pin_get(chosen, "pin_tmp")
            ext, w, h, frames = _img_info(tmp)
            if ext == "gif" and frames >= 2:
                tmp.rename(OUT / f"pinterest_{pid or 'gif'}.gif")
                print(f"GIF {w}x{h} frames={frames}", flush=True)
                return
            tmp.unlink()
            sys.exit("فایل GIF این پین انیمیشن نداره (فقط یک فریمه)")
        if has_video:
            report(force=True, title=title + " (ویدیوی متحرک؛ GIF واقعی وجود نداره)", thumb=thumb, percent=-1)
            video()
            return
        sys.exit("GIF اصلی برای این پین در دسترس نیست؛ فایل متحرکی پیدا نشد")

    if not chosen:
        sys.exit("نسخه اصلی عکس قابل دسترسی نیست؛ برای جلوگیری از دریافت نسخه کم‌کیفیت دانلود انجام نشد")
    tmp = _pin_get(chosen, "pin_tmp")
    ext, w, h, frames = _img_info(tmp)
    if not ext:
        tmp.unlink()
        sys.exit("فایل دریافتی عکس معتبر نبود")
    listed = max(images.values(), key=lambda t: t[0] * t[1], default=None)
    if listed and listed[0] * listed[1] > w * h > 0:
        alt = _pin_get(listed[2], "pin_alt")
        e2, w2, h2, _ = _img_info(alt)
        if e2 and w2 * h2 > w * h:
            print(f"listed {listed[0]}x{listed[1]} larger than original {w}x{h}; using it", flush=True)
            tmp.unlink()
            tmp, ext, w, h = alt, e2, w2, h2
        else:
            alt.unlink()
    print(f"IMAGE {ext} {w}x{h} from {chosen}", flush=True)
    tmp.rename(OUT / f"pinterest_{pid or 'image'}.{ext}")


def main():
    low = URL.lower()
    if "spotify.com" in low or "spotify.link" in low:
        spotify(URL)
    elif "pinterest." in low or "pin.it" in low:
        pinterest(URL)
    else:
        if not ytdlp(URL, audio=AUDIO):
            sys.exit("دانلود ناموفق بود. لینک رو چک کن یا بعداً دوباره امتحان کن")

    files = sorted(f for f in OUT.iterdir() if f.is_file() and f.suffix.lower().lstrip(".") in MEDIA and f.stat().st_size > 0)
    for f in OUT.iterdir():
        if f.is_file() and f not in files:
            f.unlink()
    if not files:
        sys.exit("هیچ فایلی تولید نشد")

    meta = []
    for i, f in enumerate(files, 1):
        original = f.name
        slug = re.sub(r"[^A-Za-z0-9]+", "_", f.stem).strip("_")[:40] or "file"
        new = OUT / f"{i:02d}_{slug}{f.suffix.lower()}"
        if new != f:
            f.rename(new)
        meta.append({"asset": new.name, "name": original})
    (OUT / "meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
    report(force=True, phase="uploading", percent=-1, speed=0, eta=-1)
    print(json.dumps(meta, ensure_ascii=False))


try:
    main()
except SystemExit as e:
    if e.code not in (0, None):
        report(force=True, phase="error", message=str(e.code))
    raise
except Exception as e:
    report(force=True, phase="error", message=str(e)[:300])
    raise
