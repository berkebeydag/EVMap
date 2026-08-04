"""
Fetches the places worth knowing about while a car is charging.

Thirty minutes on a charger is thirty minutes standing somewhere, and the question is
always the same: is there a coffee, a toilet, a shop. OpenStreetMap knows; the basemap
does not draw it — CARTO's raster styles carry no POI symbology at any zoom, measured.

Asked by bounding box, not by radius around each station. The obvious query is an
`around:250` per station, and it does not work: eighty of those clauses in one request
gets a 504 from two different Overpass mirrors, because `around` is evaluated per
clause while a bbox is one lookup in a spatial index. So this asks for a box at a time
and throws away what is not near a station afterwards — more bytes over the wire, a
fraction of the server's work, and it actually finishes.

Boxes are half a degree, and only the 335 that contain a charging station are asked
about. A box that times out is split in four and retried, which is the only case where
the size matters.

Resumable: each box is written as it completes and a re-run skips what is done.
"""

import io
import json
import math
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import collections

BUNDLE = 'app/src/main/assets/chargers_tr.json'
PARTIAL = 'data/pois_partial.jsonl'
OUT = 'data/pois_raw.json'

# Kumi first. The main instance rate-limits by IP and makes you wait for a slot, which
# is where the time went: measured over fifteen boxes, most came back in two or three
# seconds and a few took four hundred, and the four hundred was queueing rather than
# work. Kumi is the more generous mirror and the main instance is the fallback.
ENDPOINTS = (
    'https://overpass.kumi.systems/api/interpreter',
    'https://overpass-api.de/api/interpreter',
)

CELL = 0.5
KEEP_WITHIN_M = 250.0
PAUSE_S = 1.0
BACKOFF_S = 20.0
MAX_ROUNDS = 3

AMENITIES = 'cafe|restaurant|fast_food|fuel|pharmacy|toilets'
SHOPS = 'supermarket|convenience|bakery|mall'

HEADERS = {
    'User-Agent': 'SarjBul/1.0 (one-off bundle build; berke.beydag@gmail.com)',
    'Content-Type': 'application/x-www-form-urlencoded',
}


def stations():
    with io.open(BUNDLE, encoding='utf-8') as handle:
        data = json.load(handle)
    rows = data['stations'] if isinstance(data, dict) else data
    return [(float(s['lat']), float(s['lon'])) for s in rows]


def boxes(points):
    """Only the half-degree boxes that actually contain a charger."""
    occupied = sorted({(int(math.floor(la / CELL)), int(math.floor(lo / CELL)))
                       for la, lo in points})
    return [(i * CELL, j * CELL, (i + 1) * CELL, (j + 1) * CELL) for i, j in occupied]


def query_for(box):
    south, west, north, east = box
    bbox = '%.4f,%.4f,%.4f,%.4f' % (south, west, north, east)
    return ('[out:json][timeout:180][bbox:%s];'
            '(node["amenity"~"^(%s)$"];node["shop"~"^(%s)$"];);out;'
            % (bbox, AMENITIES, SHOPS))


def ask(box, depth=0):
    """
    One box, from whichever mirror answers.

    The mirrors are tried back to back with no pause between them. The first version
    slept thirty seconds before moving to the second, which meant every box a mirror
    refused cost thirty seconds of nothing — measured, that was the whole runtime:
    boxes that should take three seconds were taking ninety. A pause belongs after
    everything has refused, not between two things that have not been asked yet.
    """
    data = urllib.parse.urlencode({'data': query_for(box)}).encode()
    last = None
    for round_number in range(MAX_ROUNDS):
        for endpoint in ENDPOINTS:
            try:
                request = urllib.request.Request(endpoint, data=data, headers=HEADERS)
                with urllib.request.urlopen(request, timeout=180) as response:
                    return json.load(response).get('elements', [])
            except urllib.error.HTTPError as error:
                last = error
                if error.code == 504 and depth < 2:
                    # Too much in one box for this mirror: quarter it and ask again.
                    south, west, north, east = box
                    mid_lat, mid_lon = (south + north) / 2, (west + east) / 2
                    out = []
                    for quarter in ((south, west, mid_lat, mid_lon),
                                    (south, mid_lon, mid_lat, east),
                                    (mid_lat, west, north, mid_lon),
                                    (mid_lat, mid_lon, north, east)):
                        out.extend(ask(quarter, depth + 1))
                    return out
            except Exception as error:               # noqa: BLE001
                last = error
        if round_number < MAX_ROUNDS - 1:
            time.sleep(BACKOFF_S)
    print('    vazgecildi: %s' % last, flush=True)
    return []


def main():
    points = stations()
    todo = boxes(points)

    # Station index, so "is this POI near a charger" is a handful of comparisons.
    grid = collections.defaultdict(list)
    for la, lo in points:
        grid[(int(la / 0.005), int(lo / 0.005))].append((la, lo))

    def near_station(lat, lon):
        ci, cj = int(lat / 0.005), int(lon / 0.005)
        for i in range(ci - 1, ci + 2):
            for j in range(cj - 1, cj + 2):
                for sla, slo in grid.get((i, j), ()):
                    dlat = (sla - lat) * 111320.0
                    dlon = (slo - lon) * 111320.0 * math.cos(math.radians(lat))
                    if math.hypot(dlat, dlon) <= KEEP_WITHIN_M:
                        return True
        return False

    done = set()
    if os.path.exists(PARTIAL):
        with io.open(PARTIAL, encoding='utf-8') as handle:
            for line in handle:
                if line.strip():
                    done.add(json.loads(line)['box'])

    started = time.time()
    with io.open(PARTIAL, 'a', encoding='utf-8', newline='') as handle:
        for index, box in enumerate(todo):
            if index in done:
                continue
            elements = ask(box)
            rows = []
            for element in elements:
                tags = element.get('tags') or {}
                kind = tags.get('amenity') or tags.get('shop')
                lat, lon = element.get('lat'), element.get('lon')
                if not kind or lat is None or lon is None:
                    continue
                if not near_station(float(lat), float(lon)):
                    continue
                rows.append({
                    'id': element.get('id'),
                    'lat': round(float(lat), 5),
                    'lon': round(float(lon), 5),
                    'kind': kind,
                    'name': (tags.get('name') or '').strip() or None,
                })
            handle.write(json.dumps({'box': index, 'rows': rows}, ensure_ascii=False) + '\n')
            handle.flush()
            print('%3d/%d  %5d dondu  %4d tutuldu  %5.0fs'
                  % (index + 1, len(todo), len(elements), len(rows), time.time() - started),
                  flush=True)
            time.sleep(PAUSE_S)

    seen = {}
    with io.open(PARTIAL, encoding='utf-8') as handle:
        for line in handle:
            if line.strip():
                for row in json.loads(line)['rows']:
                    seen[row['id']] = row
    rows = sorted(seen.values(), key=lambda r: r['id'])
    with io.open(OUT, 'w', encoding='utf-8', newline='') as handle:
        json.dump(rows, handle, ensure_ascii=False, separators=(',', ':'))
    print('\ntekil POI: %d -> %s' % (len(rows), OUT), flush=True)


if __name__ == '__main__':
    main()
