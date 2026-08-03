"""
Merges Trugo's own station list into the bundle.

Trugo publishes the list its own map runs on, as GeoJSON, at
https://trugo.com.tr/api/station/map/ — 1,489 stations with a venue name on every one
and an AC/DC type. It is fetched once, here, to build the bundle; the app never calls
it. Nothing about this changes at runtime.

It matters because Trugo runs the chargers on Shell forecourts in Turkey and names
them accordingly — 230 of its stations say Shell — where the TomTom sweep files every
one of them as plain "Trugo".

Two things are taken from it and they are kept separate on purpose:

  * The 62 stations that are not in the bundle at all. Everything within 250 m of an
    existing Trugo, or 150 m of anything at all, is left out — the second threshold
    because Trugo's coordinate and another source's coordinate for the same forecourt
    routinely differ by a hundred metres, and one forecourt drawn twice is worse than
    one drawn under a slightly wrong brand.

  * The venue names, applied to the matched rows. This is the larger gain: 1,330
    existing rows can be told which place they are at.

Stations Trugo marks status 2 are skipped. Only status 1 is live.

Power is not taken. The feed says AC, DC or ACDC and nothing about kW, and inventing a
rating from a type is exactly the kind of plausible-looking wrong number the rest of
this app goes out of its way not to print.
"""

import io
import json
import math
import collections

BUNDLE = 'app/src/main/assets/chargers_tr.json'
FEED = 'data/trugo_stations.json'

NEW_NEAR_SAME_M = 250.0
NEW_NEAR_ANY_M = 150.0
NAME_MATCH_M = 250.0
CELL = 0.005


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
    features = feed['data']['stationList']['features']

    grid = collections.defaultdict(list)

    def index(station):
        lat, lon = float(station['lat']), float(station['lon'])
        grid[(int(lat / CELL), int(lon / CELL))].append((lat, lon, station))

    for station in bundle:
        index(station)

    def nearest(lat, lon, accept):
        best = None
        ci, cj = int(lat / CELL), int(lon / CELL)
        for i in range(ci - 1, ci + 2):
            for j in range(cj - 1, cj + 2):
                for blat, blon, station in grid.get((i, j), ()):
                    if not accept(station):
                        continue
                    distance = metres(lat, lon, blat, blon)
                    if best is None or distance < best[0]:
                        best = (distance, station)
        return best

    is_trugo = lambda s: norm(s.get('operator')) == 'trugo'
    anything = lambda s: True

    stats = collections.Counter()
    added = []
    renamed = 0

    for feature in features:
        props = feature['properties']
        if props.get('status') != 1:
            stats['Trugo pasif isaretlemis'] += 1
            continue
        lon, lat = feature['geometry']['coordinates']
        venue = (props.get('locationName') or '').strip() or None

        same = nearest(lat, lon, is_trugo)
        if same and same[0] <= NEW_NEAR_SAME_M:
            stats['zaten var'] += 1
            station = same[1]
            # Only over a name that repeats the operator; a name somebody else
            # already worked out is not improved by overwriting it.
            current = station.get('name')
            if venue and (not current or norm(current) == norm(station.get('operator'))):
                station['name'] = venue
                renamed += 1
            continue

        other = nearest(lat, lon, anything)
        if other and other[0] <= NEW_NEAR_ANY_M:
            stats['baska kayit cok yakin'] += 1
            continue

        row = {
            'lat': lat,
            'lon': lon,
            'name': venue,
            'operator': 'Trugo',
            # No kW in the feed, and a rating guessed from "DC" would be a number the
            # app would then show as though it had been told.
            'maxPowerKw': None,
            'isDc': True if props.get('type') in ('DC', 'ACDC') else False,
            'connectors': None,
            'address': None,
            'chargePoints': None,
            'source': 'trugo-feed',
            'sourceId': 'trugo:%s' % props.get('id'),
        }
        bundle.append(row)
        index(row)
        added.append(row)
        stats['EKLENDI'] += 1

    print('Trugo listesi: %d' % len(features))
    for reason, count in stats.most_common():
        print('  %-26s %5d' % (reason, count))
    print('  mekan adi konulan          %5d' % renamed)

    print('\neklenenlerden ornekler:')
    for row in added[:10]:
        print('  %-38s %s' % ((row['name'] or '')[:38], 'DC' if row['isDc'] else 'AC'))

    if envelope is not None:
        envelope['stations'] = bundle
        payload = envelope
    else:
        payload = bundle
    with io.open(BUNDLE, 'w', encoding='utf-8', newline='') as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(',', ':'))
    print('\ntoplam %d satir yazildi' % len(bundle))


if __name__ == '__main__':
    main()
