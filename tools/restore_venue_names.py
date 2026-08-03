"""
Gives stations back the name of the place they are at.

The TomTom sweep names almost every station after its operator and nothing else:
15,781 of 16,766 rows have a name identical to their operator, so a card reads "Trugo"
and the list reads "Trugo" and neither says which forecourt that is. The five-source
merge it replaced was assembled partly from the networks' own lists, and those name the
venue — "Trugo - Shell Bornova", "Yükseloğulları Gurme Çiftliği", "otoWATT - Rüya 48
Outlet AVM".

That is why Shell appeared to vanish. In Turkey the chargers on Shell forecourts are
Trugo units, and 207 rows in the old Trugo list say Shell in their name. TomTom files
them all as plain "Trugo", so a search for Shell finds nothing and a driver standing at
a Shell station sees a marker that does not mention it.

The venue name goes back on where it can be matched: same operator, within 200 m, and
only onto rows whose current name carries no information anyway. The operator field is
untouched, so the map keeps showing the brand — a map labelled with venue names would
be unreadable — and the venue shows on the card and in the list, which is where you
look once you have picked something.

Conservative on purpose. An old row is used at most once, a name is only written over a
name that repeats the operator, and anything that cannot be matched is left alone.
"""

import io
import json
import math
import collections

BUNDLE = 'app/src/main/assets/chargers_tr.json'
LEGACY = 'data/chargers_tr.merged.json'

MATCH_RADIUS_M = 200.0
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


def venue_of(station):
    """The old row's name with a redundant operator prefix removed, or None."""
    name = (station.get('name') or '').strip()
    operator = (station.get('operator') or '').strip()
    if not name:
        return None
    for separator in (' - ', ' – ', ' · '):
        prefix = operator + separator
        if operator and name.lower().startswith(prefix.lower()):
            name = name[len(prefix):].strip()
            break
    name = name.strip(' -–·')
    if not name or norm(name) == norm(operator):
        return None
    return name


def main():
    bundle, envelope = load(BUNDLE)
    legacy, _ = load(LEGACY)

    grid = collections.defaultdict(list)
    for station in bundle:
        lat, lon = float(station['lat']), float(station['lon'])
        grid[(int(lat / CELL), int(lon / CELL))].append((lat, lon, station))

    taken = set()
    stats = collections.Counter()
    changed = []

    for old in legacy:
        venue = venue_of(old)
        if not venue:
            stats['eski kayitta mekan adi yok'] += 1
            continue
        operator = norm(old.get('operator'))
        if not operator:
            stats['eski kayitta operator yok'] += 1
            continue

        lat, lon = float(old['lat']), float(old['lon'])
        best = None
        ci, cj = int(lat / CELL), int(lon / CELL)
        for i in range(ci - 1, ci + 2):
            for j in range(cj - 1, cj + 2):
                for blat, blon, station in grid.get((i, j), ()):
                    if id(station) in taken:
                        continue
                    if norm(station.get('operator')) != operator:
                        continue
                    # Only onto a name that says nothing the operator does not.
                    current = station.get('name')
                    if current and norm(current) != operator:
                        continue
                    distance = metres(lat, lon, blat, blon)
                    if distance > MATCH_RADIUS_M:
                        continue
                    if best is None or distance < best[0]:
                        best = (distance, station)

        if best is None:
            stats['eslesecek kayit bulunamadi'] += 1
            continue

        distance, station = best
        taken.add(id(station))
        station['name'] = venue
        stats['isim geri konuldu'] += 1
        if len(changed) < 12:
            changed.append((round(distance), station.get('operator'), venue))

    for reason, count in stats.most_common():
        print('%-32s %6d' % (reason, count))

    print('\nornekler:')
    for distance, operator, venue in changed:
        print('  %3dm  %-14s %s' % (distance, (operator or '')[:14], venue))

    shell = [s for s in bundle if 'shell' in norm(s.get('name'))]
    print('\nadinda shell gecen kayit: %d' % len(shell))
    print('bunlardan Trugo isletmesinde: %d'
          % sum(1 for s in shell if norm(s.get('operator')) == 'trugo'))

    uninformative = sum(1 for s in bundle if s.get('name') and norm(s['name']) == norm(s.get('operator')))
    print('adi hala sadece operator olan: %d / %d' % (uninformative, len(bundle)))

    if envelope is not None:
        envelope['stations'] = bundle
        payload = envelope
    else:
        payload = bundle
    with io.open(BUNDLE, 'w', encoding='utf-8', newline='') as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(',', ':'))
    print('\nyazildi: %s' % BUNDLE)


if __name__ == '__main__':
    main()
