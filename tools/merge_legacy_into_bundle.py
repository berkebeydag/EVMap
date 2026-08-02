"""
Adds the places the TomTom sweep does not have, from the five-source merge it replaced.

The sweep is better data — it carries power and connector types on almost every row,
where the merge left both blank most of the time — but it is a POI database, and a POI
database has whatever somebody told it about. The merge was assembled from Open Charge
Map, ZES's and Trugo's own lists, the İBB feed and OpenStreetMap, and between them
those five know about places TomTom simply does not.

Measured: 783 rows in the merge have no sweep station within 250 m. Those are not
duplicates under any reading — the app groups records into one site at 60 m, and at 150
m for two records naming the same operator — so anything past 250 m is a different
forecourt.

The rule is deliberately one-sided. It is easy to reject a real station and hard to
notice one invented, so a merge row is added only when nothing plausibly the same
exists near it:

  * nothing at all from the sweep within 250 m, and
  * nothing from the sweep naming the same operator within 600 m, because a forecourt
    can be recorded at its entrance by one source and at its chargers by another, and
    600 m is past the worst of that.

Rows are also deduplicated against each other before being written: the merge was five
sources deep and has its own internal repeats, which would otherwise arrive as pairs.

They are written with source "legacy-merge" rather than their original source names.
The seeder clears rows by source and records what it last seeded, so a distinct name is
what lets a future bundle remove exactly these again — reusing "ocm" or "zes" would put
them back in the set the seeder already treats as orphaned.
"""

import io
import json
import math
import collections

BUNDLE = 'app/src/main/assets/chargers_tr.json'
LEGACY = 'data/chargers_tr.merged.json'

ANY_RADIUS_M = 250.0
SAME_OPERATOR_RADIUS_M = 600.0
SELF_RADIUS_M = 120.0
CELL = 0.01  # ~1.1 km, comfortably larger than the widest radius above


def load(path):
    """Returns (rows, envelope) so whatever wraps the rows survives a round trip."""
    with io.open(path, encoding='utf-8') as handle:
        data = json.load(handle)
    if isinstance(data, dict) and 'stations' in data:
        return data['stations'], data
    return data, None


def coords(station):
    try:
        return float(station['lat']), float(station['lon'])
    except (KeyError, TypeError, ValueError):
        return None


def metres(lat1, lon1, lat2, lon2):
    dlat = (lat2 - lat1) * 111320.0
    dlon = (lon2 - lon1) * 111320.0 * math.cos(math.radians(lat1))
    return math.hypot(dlat, dlon)


COMBINING_DOT = chr(0x307)


def clean_text(value):
    """
    Strips the stray dot the merge's operator names carry.

    Turkish capital I lowercases to "i" plus a combining dot above, and something
    upstream of the merge round-tripped ALL-CAPS registry names through that: the file
    literally contains "Di" + U+0307 + "jital". It renders as Di̇jital, which is a
    typo with a code point behind it. The dot carries no meaning on a lower-case i in
    any language, so it goes.
    """
    if not value:
        return value
    return value.replace(COMBINING_DOT, '')


def norm_operator(value):
    return clean_text(value or '').strip().lower()


class Index:
    """Grid of stations so a lookup compares against neighbours, not the whole country."""

    def __init__(self):
        self.cells = collections.defaultdict(list)

    def add(self, lat, lon, station):
        self.cells[(int(lat / CELL), int(lon / CELL))].append((lat, lon, station))

    def around(self, lat, lon):
        ci, cj = int(lat / CELL), int(lon / CELL)
        for i in range(ci - 1, ci + 2):
            for j in range(cj - 1, cj + 2):
                for entry in self.cells.get((i, j), ()):
                    yield entry


def main():
    bundle, envelope = load(BUNDLE)
    legacy, _ = load(LEGACY)

    sweep = Index()
    for station in bundle:
        point = coords(station)
        if point:
            sweep.add(point[0], point[1], station)

    added = Index()
    kept = []
    rejected = collections.Counter()

    for station in legacy:
        point = coords(station)
        if not point:
            rejected['no coordinates'] += 1
            continue
        lat, lon = point
        operator = norm_operator(station.get('operator'))

        too_close = False
        for slat, slon, other in sweep.around(lat, lon):
            distance = metres(lat, lon, slat, slon)
            if distance <= ANY_RADIUS_M:
                rejected['sweep within %dm' % ANY_RADIUS_M] += 1
                too_close = True
                break
            if operator and distance <= SAME_OPERATOR_RADIUS_M and \
                    norm_operator(other.get('operator')) == operator:
                rejected['same operator within %dm' % SAME_OPERATOR_RADIUS_M] += 1
                too_close = True
                break
        if too_close:
            continue

        for alat, alon, _ in added.around(lat, lon):
            if metres(lat, lon, alat, alon) <= SELF_RADIUS_M:
                rejected['duplicate inside the merge'] += 1
                too_close = True
                break
        if too_close:
            continue

        row = {
            'lat': lat,
            'lon': lon,
            'name': clean_text(station.get('name')),
            'operator': clean_text(station.get('operator')),
            'maxPowerKw': station.get('maxPowerKw'),
            'isDc': station.get('isDc'),
            'connectors': station.get('connectors'),
            'address': clean_text(station.get('address')),
            'chargePoints': station.get('chargePoints'),
            'source': 'legacy-merge',
            'sourceId': 'legacy:%s:%s' % (station.get('source'), station.get('sourceId')),
        }
        kept.append(row)
        added.add(lat, lon, row)

    print('sweep rows      %6d' % len(bundle))
    print('merge rows      %6d' % len(legacy))
    for reason, count in rejected.most_common():
        print('  rejected: %-34s %6d' % (reason, count))
    print('added           %6d' % len(kept))
    print('total           %6d' % (len(bundle) + len(kept)))

    operators = collections.Counter(
        (row.get('operator') or '(isimsiz)') for row in kept
    )
    print('\ntop operators added:')
    for name, count in operators.most_common(15):
        print('  %5d  %s' % (count, name))

    known = {norm_operator(s.get('operator')) for s in bundle}
    fresh = sorted({row.get('operator') for row in kept
                    if norm_operator(row.get('operator')) not in known and row.get('operator')})
    print('\noperators the bundle did not have at all: %d' % len(fresh))
    for name in fresh[:25]:
        print('   ', name)

    # The envelope matters. Written as a bare array the seeder finds no `stations` key,
    # parses nothing and returns silently — the map comes up empty and says nothing
    # about why, which is exactly what happened the first time this ran.
    rows = bundle + kept
    if envelope is not None:
        envelope['stations'] = rows
        payload = envelope
    else:
        payload = rows

    with io.open(BUNDLE, 'w', encoding='utf-8', newline='') as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(',', ':'))
    print('\nwritten to %s — %d rows, envelope %s'
          % (BUNDLE, len(rows), 'preserved' if envelope is not None else 'none'))


if __name__ == '__main__':
    main()
