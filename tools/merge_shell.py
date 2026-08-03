"""
Merges Shell's own list of charging forecourts into the bundle.

The measurement this produced is the answer to the question that started it. Of the 340
Shell sites in Turkey that charge, 211 already have a Trugo within 150 m and 112 have a
Shell Recharge. Six were missing. The stations were never absent — they were filed
under the operator that runs them, which for a Shell forecourt in Turkey is Trugo, and
under a name that said only "Trugo".

So the useful part of this feed is not the stations. It is the names. A row that says
"Trugo" becomes "Shell Çınarlı", and a search for Shell finds the place a driver is
standing at.

The naming is deliberately narrow. It is written only onto a row whose name repeats its
operator — a name somebody else already worked out is not improved by replacing it —
and only within 150 m, which is inside the radius the app already treats as one site.
The operator field is never touched: the forecourt is Shell's, the charger is Trugo's,
and the app should keep saying whose charger you are about to plug into.

Shell writes its site names in capitals with a trailing full stop — "ÇINARLI." — so
they are recased on the way in, in Turkish, where I and İ do not lowercase the way they
do anywhere else.
"""

import io
import json
import math
import collections

BUNDLE = 'app/src/main/assets/chargers_tr.json'
FEED = 'data/shell_stations.json'

NEAR_M = 150.0
CELL = 0.005

TR_LOWER = {'I': 'ı', 'İ': 'i'}
TR_UPPER = {'i': 'İ', 'ı': 'I'}


def tr_lower(text):
    return ''.join(TR_LOWER.get(ch, ch) for ch in text).lower()


def tr_title(text):
    """Turkish title case, without the combining dot that str.lower() leaves behind."""
    words = []
    for word in tr_lower(text).split():
        if not word:
            continue
        head = TR_UPPER.get(word[0], word[0].upper())
        words.append(head + word[1:])
    return ' '.join(words)


def shell_name(record):
    raw = (record.get('name') or '').strip().strip('.').strip()
    if not raw:
        return None
    name = tr_title(raw)
    return name if tr_lower(name).startswith('shell') else 'Shell ' + name


def load(path):
    with io.open(path, encoding='utf-8') as handle:
        data = json.load(handle)
    if isinstance(data, dict) and 'stations' in data:
        return data['stations'], data
    return data, None


def norm(value):
    return (value or '').strip().lower()


def metres(lat1, lon1, lat2, lon2):
    dlat = (lat2 - lat1) * 111320.0
    dlon = (lon2 - lon1) * 111320.0 * math.cos(math.radians(lat1))
    return math.hypot(dlat, dlon)


def main():
    bundle, envelope = load(BUNDLE)
    with io.open(FEED, encoding='utf-8') as handle:
        feed = json.load(handle)

    grid = collections.defaultdict(list)

    def index(station):
        lat, lon = float(station['lat']), float(station['lon'])
        grid[(int(lat / CELL), int(lon / CELL))].append((lat, lon, station))

    for station in bundle:
        index(station)

    def nearest(lat, lon):
        best = None
        ci, cj = int(lat / CELL), int(lon / CELL)
        for i in range(ci - 1, ci + 2):
            for j in range(cj - 1, cj + 2):
                for blat, blon, station in grid.get((i, j), ()):
                    distance = metres(lat, lon, blat, blon)
                    if best is None or distance < best[0]:
                        best = (distance, station)
        return best

    stats = collections.Counter()
    renamed_by_operator = collections.Counter()
    added = []

    for record in feed:
        lat, lon = float(record['lat']), float(record['lng'])
        name = shell_name(record)
        best = nearest(lat, lon)

        if best and best[0] <= NEAR_M:
            stats['zaten var'] += 1
            station = best[1]
            current = station.get('name')
            if name and (not current or norm(current) == norm(station.get('operator'))):
                station['name'] = name
                renamed_by_operator[station.get('operator') or '?'] += 1
            continue

        row = {
            'lat': lat,
            'lon': lon,
            'name': name,
            'operator': 'Shell Recharge',
            # Shell's finder says a site charges, not how fast.
            'maxPowerKw': None,
            'isDc': None,
            'connectors': None,
            'address': (record.get('formatted_address') or '').replace('\n', ', ').strip() or None,
            'chargePoints': None,
            'source': 'shell-feed',
            'sourceId': 'shell:%s' % record.get('id'),
        }
        bundle.append(row)
        index(row)
        added.append(row)
        stats['EKLENDI'] += 1

    print('Shell beslemesi: %d' % len(feed))
    for reason, count in stats.most_common():
        print('  %-22s %5d' % (reason, count))
    print('\nmekan adi konulanlar, isletmeciye gore:')
    for operator, count in renamed_by_operator.most_common(8):
        print('  %-20s %5d' % (operator[:20], count))
    print('\neklenenler:')
    for row in added:
        print('  %s' % row['name'])

    if envelope is not None:
        envelope['stations'] = bundle
        payload = envelope
    else:
        payload = bundle
    with io.open(BUNDLE, 'w', encoding='utf-8', newline='') as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(',', ':'))
    print('\ntoplam %d satir' % len(bundle))


if __name__ == '__main__':
    main()
