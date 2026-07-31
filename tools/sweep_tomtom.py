"""
Sweeps TomTom for every charging station in Türkiye and writes them out.

Mirrors the quadtree in TomTomChargerSource: query a box, and if it comes back
full, split it into quarters and queue those.

Writes after every batch, not at the end. The first version of this script held
everything in memory and only printed a summary, so when it finished, 10,734
stations and a month's request allowance went with it. A sweep costs about half
the monthly free allowance, which makes losing the results expensive enough that
the file is flushed as it goes.

The output is for loading onto the licence holder's own device. It is deliberately
not merged into app/src/main/assets — that file is published on a public repository,
and TomTom's terms forbid compiling and redistributing their data.

Usage:  TOMTOM_KEY=... python tools/sweep_tomtom.py [out.json]
"""
import io
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, deque

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

KEY = os.environ["TOMTOM_KEY"]
OUT = sys.argv[1] if len(sys.argv) > 1 else "tomtom_tr.json"
PAGE = 100
MAX_RADIUS = 50_000.0
MAX_DEPTH = 6
HARD_CAP = 900           # so a bug cannot empty the monthly allowance
SPACING_S = 0.25         # measured: unspaced runs start collecting 429s at ~200
BUNDLE = "app/src/main/assets/chargers_tr.json"
STATE = ".tomtom_sweep_state.json"

TURKEY = (35.8, 25.6, 42.2, 44.9)   # minLat, minLon, maxLat, maxLon

#: Seed tile size in km, matched to the query radius so one request covers a tile.
TILE_KM = 50.0
CORNER_MARGIN = 1.03     # so a station on a tile corner is unambiguously inside


def centre(b):
    return (b[0] + b[2]) / 2, (b[1] + b[3]) / 2


def radius(b):
    lat, _ = centre(b)
    h = (b[2] - b[0]) * 111_320.0
    w = (b[3] - b[1]) * 111_320.0 * math.cos(math.radians(lat))
    return math.hypot(h, w) / 2 * CORNER_MARGIN


def quarters(b):
    mlat, mlon = centre(b)
    return [(b[0], b[1], mlat, mlon), (b[0], mlon, mlat, b[3]),
            (mlat, b[1], b[2], mlon), (mlat, mlon, b[2], b[3])]


def seed_tiles():
    """
    Where to look: the cells our own stations occupy, not the country's outline.

    Tiling Türkiye's bounding box down to the query radius is 1,024 requests before
    a single station is found, most of them landing on sea, empty steppe and the
    neighbouring countries. The 6,102 coordinates already in the bundle are a far
    better map of where chargers are: covering them takes 305 tiles, and anywhere a
    charger exists in Türkiye one of those points is within range.

    The longitude step has to come from the row's latitude rather than each
    station's own, or the tile a station is filed under does not contain it.
    """
    try:
        rows = json.load(open(BUNDLE, encoding="utf-8"))["stations"]
    except Exception:
        return [(TURKEY, 0)]
    if not rows:
        return [(TURKEY, 0)]

    step_lat = TILE_KM / 111.32

    def step_lon(row):
        return TILE_KM / (111.32 * math.cos(math.radians((row + 0.5) * step_lat)))

    cells = set()
    for s in rows:
        row = math.floor(s["lat"] / step_lat)
        cells.add((row, math.floor(s["lon"] / step_lon(row))))

    return [((row * step_lat, col * step_lon(row),
              (row + 1) * step_lat, (col + 1) * step_lon(row)), 0)
            for row, col in cells]


def load_state():
    """Boxes a previous run did not get to."""
    try:
        with open(STATE, encoding="utf-8") as fh:
            return [(tuple(b), d) for b, d in json.load(fh)]
    except Exception:
        return []


def save_state(remaining):
    if not remaining:
        try:
            os.remove(STATE)
        except OSError:
            pass
        return
    with open(STATE, "w", encoding="utf-8") as fh:
        json.dump([[list(b), d] for b, d in remaining], fh)


def query(b, r):
    lat, lon = centre(b)
    url = (f"https://api.tomtom.com/search/2/nearbySearch/.json?key={KEY}"
           f"&lat={lat}&lon={lon}&radius={int(r)}&categorySet=7309"
           f"&limit={PAGE}&countrySet=TR")
    req = urllib.request.Request(url, headers={"User-Agent": "IoniqScope/0.1"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8")).get("results") or []


def load_found():
    """
    Everything a previous run already fetched.

    Without this a resumed run starts with an empty set and the first flush
    overwrites the file with only the handful of stations that run collected — so
    continuing a sweep would destroy the sweep it was continuing. The stations are
    keyed by TomTom's own id, so re-reading them cannot introduce duplicates.
    """
    try:
        with open(OUT, encoding="utf-8") as fh:
            return {x["id"]: x for x in json.load(fh)["stations"] if x.get("id")}
    except Exception:
        return {}


found, brands = load_found(), Counter()


def flush():
    """
    Write what we have so far. Cheap next to the cost of re-fetching it.

    Written to a temporary file and moved into place: a crash halfway through a
    20 MB dump would otherwise leave a truncated file where the results used to be,
    which is the one failure this whole arrangement exists to prevent.
    """
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump({"stations": list(found.values())}, fh,
                  ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, OUT)

requests = 0
resume = load_state()
queue = deque(resume or seed_tiles())
truncated = False
stopped = None

if resume:
    print(f"kaldığı yerden devam ediliyor: {len(resume)} bölge kaldı\n")
else:
    print(f"tohum bölge: {len(queue)}\n")

while queue and requests < HARD_CAP:
    box, depth = queue.popleft()
    r = radius(box)
    if r > MAX_RADIUS and depth < MAX_DEPTH:
        queue.extend((q, depth + 1) for q in quarters(box))
        continue

    requests += 1
    try:
        results = query(box, min(r, MAX_RADIUS))
    except urllib.error.HTTPError as exc:
        # Out of allowance. Every further request fails the same way and is charged
        # the same, so stop and keep the box for next time.
        if exc.code == 403:
            queue.appendleft((box, depth))
            stopped = "TomTom istek hakkı doldu"
            break
        if exc.code == 429:
            time.sleep(3.0)
            queue.appendleft((box, depth))
            requests -= 1
            continue
        print(f"  istek başarısız: {exc}")
        continue
    except Exception as exc:
        print(f"  istek başarısız: {exc}")
        continue

    time.sleep(SPACING_S)

    for x in results:
        found[x["id"]] = x
        b = ((x.get("poi") or {}).get("brands") or [{}])[0].get("name")
        if b:
            brands[b] += 1

    if len(results) >= PAGE:
        if depth < MAX_DEPTH:
            queue.extend((q, depth + 1) for q in quarters(box))
        else:
            truncated = True

    if requests % 50 == 0:
        flush()
        print(f"  {requests} istek, {len(found)} istasyon… ({OUT} güncellendi)")

flush()
save_state(list(queue))

powered = sum(1 for x in found.values()
              if any(c.get("ratedPowerKW")
                     for c in ((x.get("chargingPark") or {}).get("connectors") or [])))

if stopped:
    print(f"\nDURDU: {stopped}.")
    print(f"Kalan {len(queue)} bölge {STATE} dosyasına yazıldı — hak yenilendiğinde")
    print("aynı komutu çalıştır, kaldığı yerden devam eder.")

print(f"\nyazıldı     : {OUT}")
print(f"istek       : {requests}")
print(f"istasyon    : {len(found)}")
print(f"gücü bilinen: {powered} (%{100*powered//max(len(found),1)})")
print(f"kalan kuyruk: {len(queue)}  |  derinlik sınırına dayanan: {truncated}")
print("\nen sık markalar:")
for b, n in brands.most_common(18):
    print(f"  {n:>5}  {b}")
