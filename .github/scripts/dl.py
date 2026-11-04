import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

URL = os.environ["URL"].strip()
QUALITY = os.environ.get("QUALITY", "720").strip()
AUDIO = os.environ.get("AUDIO", "false") == "true" or QUALITY == "audio"
WARP = os.environ.get("PROXY", "")
OUT = Path("out")
OUT.mkdir(exist_ok=True)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
MEDIA = {"mp4", "mkv", "webm", "mov", "mp3", "m4a", "opus", "ogg", "wav", "flac", "gif", "png", "jpg", "jpeg", "webp"}


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace"), r.geturl()


def save_file(url, path):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r, open(path, "wb") as f:
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)


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


def ytdlp(target, name=None, audio=False, search=False):
    template = str(OUT / ((name if name else "%(title).80s") + ".%(ext)s"))
    base = [
        "yt-dlp", "--no-warnings", "--no-mtime", "--retries", "10",
        "--fragment-retries", "10", "--concurrent-fragments", "4", "-o", template,
    ]
    base += ["--playlist-items", "1"] if search else ["--no-playlist"]
    if audio:
        base += ["-x", "--audio-format", "mp3", "--audio-quality", "0", "--embed-metadata"]
    else:
        base += ["-f", video_format(), "--merge-output-format", "mp4"]
    yt = target.startswith("ytsearch") or "youtu" in target or "instagram" in target
    if yt and WARP:
        proxies = [WARP, ""]
    elif WARP:
        proxies = ["", WARP]
    else:
        proxies = [""]
    extras = [[], ["--extractor-args", "youtube:player_client=android,ios"], ["--js-runtimes", "node"]] if yt else [[]]
    for p in proxies:
        for extra in extras:
            cmd = base + (["--proxy", p] if p else []) + extra + [target]
            if subprocess.run(cmd).returncode == 0:
                return True
    return False


def spotify(url):
    if "open.spotify.com" not in url:
        _, url = fetch(url)
    m = re.search(r"open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist)/([A-Za-z0-9]+)", url)
    if not m:
        sys.exit("bad spotify link")
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
            sys.exit("spotify metadata not found")
        tracks = [(title, "")]
    for title, artist in tracks:
        q = f"{artist} - {title}" if artist else title
        ytdlp(f"ytsearch1:{q} audio", name=safe(q), audio=True, search=True)


def pinterest(url):
    html, _ = fetch(url)
    markers = ['"video_list"', '"V_720P"', "og:video", '"V_HLSV3', '"V_EXP']
    if any(k in html for k in markers):
        if ytdlp(url):
            return
        mp4 = re.search(r'https:\\?/\\?/v\d*\.pinimg\.com[^"\\]*?\.mp4', html)
        if mp4:
            save_file(mp4.group(0).replace("\\/", "/"), OUT / "pin_video.mp4")
            return
        sys.exit("pinterest video not found")
    og = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)', html) or re.search(
        r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:image["\']', html
    )
    if not og:
        sys.exit("pinterest image not found")
    u = og.group(1).replace("&amp;", "&")
    ext = u.rsplit(".", 1)[-1].split("?")[0].lower()
    if ext not in ("gif", "png", "webp", "jpg", "jpeg"):
        ext = "jpg"
    target = OUT / f"pin_image.{ext}"
    up = re.sub(r"pinimg\.com/\d+x/", "pinimg.com/originals/", u)
    try:
        save_file(up, target)
    except Exception:
        save_file(u, target)


low = URL.lower()
if "spotify.com" in low or "spotify.link" in low:
    spotify(URL)
elif "pinterest." in low or "pin.it" in low:
    pinterest(URL)
else:
    if not ytdlp(URL, audio=AUDIO):
        sys.exit("download failed")

files = sorted(f for f in OUT.iterdir() if f.is_file() and f.suffix.lower().lstrip(".") in MEDIA and f.stat().st_size > 0)
for f in OUT.iterdir():
    if f.is_file() and f not in files:
        f.unlink()
if not files:
    sys.exit("no files produced")

meta = []
for i, f in enumerate(files, 1):
    original = f.name
    slug = re.sub(r"[^A-Za-z0-9]+", "_", f.stem).strip("_")[:40] or "file"
    new = OUT / f"{i:02d}_{slug}{f.suffix.lower()}"
    if new != f:
        f.rename(new)
    meta.append({"asset": new.name, "name": original})
(OUT / "meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
print(json.dumps(meta, ensure_ascii=False))
