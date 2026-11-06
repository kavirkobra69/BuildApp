import json
import os
import re
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


def pinterest(url):
    kind = QUALITY if QUALITY in ("image", "gif") else "auto"
    html, _ = fetch(url)
    html = html.replace("\\/", "/")
    is_video = any(k in html for k in ['"video_list"', '"V_720P"', "og:video", '"V_HLSV3', '"V_EXP'])
    if kind == "auto" and is_video or (kind == "image" and "og:video" in html):
        if kind == "image":
            sys.exit("این پین ویدیوئه نه عکس. دکمه دانلود معمولی رو بزن")
        if ytdlp(url):
            return
        mp4 = re.search(r"https://v\d*\.pinimg\.com[^\"\\]*?\.mp4", html)
        if mp4:
            save_file(mp4.group(0), OUT / "pin_video.mp4")
            return
        sys.exit("ویدیوی پینترست پیدا نشد")

    found = []
    for m in re.finditer(r'"orig"\s*:\s*\{([^{}]*)\}', html):
        body = m.group(1)
        u = re.search(r'"url"\s*:\s*"([^"]+)"', body)
        w = re.search(r'"width"\s*:\s*(\d+)', body)
        h = re.search(r'"height"\s*:\s*(\d+)', body)
        if u:
            found.append((int(w.group(1)) * int(h.group(1)) if w and h else 1, u.group(1)))
    found.sort(key=lambda x: -x[0])
    cands = [u for _, u in found]
    for m in re.finditer(r'https://i\.pinimg\.com/(?:\d+x|originals)/[A-Za-z0-9/_\-]+\.(?:jpg|jpeg|png|gif|webp)', html):
        cands.append(m.group(0))
    og = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)', html) or re.search(
        r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:image["\']', html
    )
    if og:
        cands.insert(0, og.group(1).replace("&amp;", "&"))
    if not cands:
        sys.exit("آدرس عکس این پین پیدا نشد")

    report(force=True, title="Pinterest", thumb=cands[0], percent=-1)
    tried, chosen = set(), None
    exts = ["gif", "jpg", "png", "webp", "jpeg"] if kind == "gif" else ["jpg", "png", "webp", "gif", "jpeg"]
    for c in cands:
        base = re.sub(r"pinimg\.com/\d+x/", "pinimg.com/originals/", c).rsplit(".", 1)[0]
        own = c.rsplit(".", 1)[-1].split("?")[0].lower()
        for e in ([own] if own in exts else []) + exts:
            u = f"{base}.{e}"
            if u in tried:
                continue
            tried.add(u)
            if kind == "gif" and e != "gif":
                continue
            if _pin_probe(u):
                chosen = u
                break
        if chosen:
            break
    if not chosen:
        if kind == "gif":
            sys.exit("برای این پین GIF اصلی قابل دسترسی نیست")
        for c in cands:
            if "/originals/" not in c and _pin_probe(c):
                chosen = c
                break
        if not chosen:
            sys.exit("نسخه اصلی عکس قابل دسترسی نیست")
        print("WARNING: original not accessible, using best available", flush=True)
    tmp = OUT / "pin_image.tmp"
    save_file(chosen, tmp)
    ext = _magic_ext(tmp)
    if not ext:
        tmp.unlink()
        sys.exit("فایل دریافتی عکس معتبر نبود")
    if kind == "gif" and ext != "gif":
        tmp.unlink()
        sys.exit("برای این پین GIF اصلی قابل دسترسی نیست")
    tmp.rename(OUT / f"pin_image.{ext}")


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
