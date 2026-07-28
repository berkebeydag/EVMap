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
import urllib.request
from collections import Counter, deque

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

KEY = os.environ["TOMTOM_KEY"]
OUT = sys.argv[1] if len(sys.argv) > 1 else "tomtom_tr.json"
PAGE = 100
MAX_RADIUS = 50_000.0
MAX_DEPTH = 6
HARD_CAP = 1200          # so a bug cannot empty the monthly allowance

TURKEY = (35.8, 25.6, 42.2, 44.9)   # minLat, minLon, maxLat, maxLon


def centre(b):
    return (b[0] + b[2]) / 2, (b[1] + b[3]) / 2


def radius(b):
    lat, _ = centre(b)
    h = (b[2] - b[0]) * 111_320.0
    w = (b[3] - b[1]) * 111_320.0 * math.cos(math.radians(lat))
    return math.hypot(h, w) / 2


def quarters(b):
    mlat, mlon = centre(b)
    return [(b[0], b[1], mlat, mlon), (b[0], mlon, mlat, b[3]),
            (mlat, b[1], b[2], mlon), (mlat, mlon, b[2], b[3])]


def query(b, r):
    lat, lon = centre(b)
    url = (f"https://api.tomtom.com/search/2/nearbySearch/.json?key={KEY}"
           f"&lat={lat}&lon={lon}&radius={int(r)}&categorySet=7309"
           f"&limit={PAGE}&countrySet=TR")
    req = urllib.request.Request(url, headers={"User-Agent": "IoniqScope/0.1"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8")).get("results") or []


found, brands = {}, Counter()


def flush():
    """Write what we have so far. Cheap next to the cost of re-fetching it."""
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({"stations": list(found.values())}, fh,
                  ensure_ascii=False, separators=(",", ":"))

requests = 0
queue = deque([(TURKEY, 0)])
truncated = False

while queue and requests < HARD_CAP:
    box, depth = queue.popleft()
    r = radius(box)
    if r > MAX_RADIUS and depth < MAX_DEPTH:
        queue.extend((q, depth + 1) for q in quarters(box))
        continue

    requests += 1
    try:
        results = query(box, min(r, MAX_RADIUS))
    except Exception as exc:
        print(f"  istek başarısız: {exc}")
        continue

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

powered = sum(1 for x in found.values()
              if any(c.get("ratedPowerKW")
                     for c in ((x.get("chargingPark") or {}).get("connectors") or [])))

print(f"\nistek       : {requests}")
print(f"istasyon    : {len(found)}")
print(f"gücü bilinen: {powered} (%{100*powered//max(len(found),1)})")
print(f"kalan kuyruk: {len(queue)}  |  derinlik sınırına dayanan: {truncated}")
print("\nen sık markalar:")
for b, n in brands.most_common(18):
    print(f"  {n:>5}  {b}")
