"""
Measures whether TomTom is worth adding as a charging-station source.

Deliberately a sample, not a sweep. TomTom's POI search returns at most a hundred
results per call, so covering Türkiye would take hundreds of requests against a
free key before anyone has decided the data is any good. Instead this asks a
handful of places — the three biggest cities, a holiday coast, an eastern city and
a motorway corridor — and for each one compares what TomTom knows against what the
bundle already holds.

The question it answers is the only one that matters right now: does TomTom carry
the networks we cannot reach — Eşarj, Voltrun, Sharz, Otowatt — and does it carry
the power figures that are missing from two thirds of our sites?

Usage:  TOMTOM_KEY=... python tools/measure_tomtom.py
"""
import io
import json
import math
import os
import sys
import urllib.parse
import urllib.request
from collections import Counter

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

#: The five-source merge, not the shipped asset. The asset is now itself a TomTom
#: sweep, so comparing against it would only measure TomTom against TomTom and report
#: that it adds nothing.
BUNDLE = "data/chargers_tr.merged.json"

#: TomTom's POI category for an electric vehicle charging station.
EV_CATEGORY = 7309

#: name, lat, lon, radius in metres. Chosen to cover a dense city, a sprawling one,
#: a coastal strip, somewhere far from the west, and open motorway.
SAMPLES = [
    ("İstanbul / Avrupa", 41.045, 28.960, 12_000),
    ("Ankara / Çankaya", 39.905, 32.830, 12_000),
    ("İzmir / Konak", 38.420, 27.140, 12_000),
    ("Antalya / Muratpaşa", 36.885, 30.705, 12_000),
    ("Gaziantep", 37.065, 37.380, 12_000),
    ("Bolu / D-100 koridoru", 40.735, 31.600, 20_000),
]


def fetch(key, lat, lon, radius):
    """One nearby search. 100 is the documented maximum page size."""
    params = urllib.parse.urlencode({
        "key": key,
        "lat": lat,
        "lon": lon,
        "radius": radius,
        "categorySet": EV_CATEGORY,
        "limit": 100,
        "countrySet": "TR",
    })
    url = f"https://api.tomtom.com/search/2/nearbySearch/.json?{params}"
    req = urllib.request.Request(url, headers={"User-Agent": "IoniqScope/0.1"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def metres(lat1, lon1, lat2, lon2):
    return math.hypot((lat1 - lat2) * 111_320.0,
                      (lon1 - lon2) * 111_320.0 * math.cos(math.radians(lat1)))


def main():
    key = os.environ.get("TOMTOM_KEY", "").strip()
    if not key:
        print("TOMTOM_KEY is not set.")
        return 1

    ours = json.load(open(BUNDLE, encoding="utf-8"))["stations"]

    grand_tt = grand_new = grand_power = 0
    brands = Counter()

    for name, lat, lon, radius in SAMPLES:
        try:
            data = fetch(key, lat, lon, radius)
        except Exception as exc:
            print(f"{name}: request failed — {exc}")
            continue

        results = data.get("results") or []
        mine = [s for s in ours if metres(lat, lon, s["lat"], s["lon"]) <= radius]

        new = 0
        withpower = 0
        for r in results:
            pos = r.get("position") or {}
            rlat, rlon = pos.get("lat"), pos.get("lon")
            if rlat is None:
                continue

            park = r.get("chargingPark") or {}
            powers = [c.get("ratedPowerKW") for c in (park.get("connectors") or [])]
            if any(powers):
                withpower += 1

            brand = ((r.get("poi") or {}).get("brands") or [{}])[0].get("name") \
                or (r.get("poi") or {}).get("name")
            if brand:
                brands[brand] += 1

            # Same 200 m the bundle uses for one brand seen by two sources.
            if not any(metres(rlat, rlon, s["lat"], s["lon"]) < 200 for s in mine):
                new += 1

        capped = " (100 sınırına dayandı — gerçek sayı daha yüksek)" \
            if len(results) >= 100 else ""
        print(f"{name:<24} TomTom {len(results):>4}{capped}"
              f" | bizde {len(mine):>4} | bizde olmayan {new:>4}"
              f" | gücü bilinen {withpower:>4}")

        grand_tt += len(results)
        grand_new += new
        grand_power += withpower

    print(f"\ntoplam: TomTom {grand_tt} | bizde olmayan {grand_new} "
          f"| gücü bilinen {grand_power} (%{100*grand_power//max(grand_tt,1)})")
    print("\nen sık markalar:")
    for b, n in brands.most_common(15):
        print(f"  {n:>4}  {b}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
