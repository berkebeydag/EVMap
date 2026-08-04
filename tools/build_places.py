"""
Builds the gazetteer the search box looks places up in.

The map could only be searched for stations — type "Balçova" and you got whatever
station happened to have Balçova in its name. A driver planning tomorrow wants to look
at a place, not a station: where am I going, and what is around it.

The places come out of the stations themselves. Every address in the bundle is
structured the same way —

    Bahçelerarası, Mithat Paşa Caddesi 28, 35330, Balçova, Izmir
    [mahalle]      [street]                [pk]   [ilçe]   [il]

— so the last field is the province, the one before it the district, and where the
address is long enough the first is the neighbourhood. Each place is placed at the mean
of its own stations.

Deriving it this way rather than shipping an official gazetteer has a property worth
having: the only places you can search for are places the app can actually answer
about. There is no Turkish district in this file that would take you somewhere with
nothing to show.

Neighbourhoods are kept only where at least two stations agree on them. One station is
not evidence that a name is a place rather than a mis-parsed street.
"""

import io
import json
import collections
import unicodedata

BUNDLE = 'app/src/main/assets/chargers_tr.json'
OUT = 'app/src/main/assets/places_tr.json'

MIN_STATIONS_FOR_NEIGHBOURHOOD = 2

# Things that turn up in the neighbourhood slot but are streets, not places.
STREET_WORDS = (
    'cadde', 'sokak', 'sok.', 'bulvar', 'yolu', 'yol', 'mevkii', 'karayolu',
    'otoyol', 'kavşağı', 'no:', 'küme evler', 'sitesi', 'plaza', 'avm',
)


def norm(text):
    return (text or '').strip()


def looks_like_street(name):
    low = name.lower()
    return any(word in low for word in STREET_WORDS) or any(ch.isdigit() for ch in name)


def fold(text):
    """A search key: lower case, no accents, Turkish dotted i handled first."""
    text = text.replace('I', 'ı').replace('İ', 'i').lower()
    stripped = unicodedata.normalize('NFD', text)
    return ''.join(c for c in stripped if unicodedata.category(c) != 'Mn')


def main():
    with io.open(BUNDLE, encoding='utf-8') as handle:
        bundle = json.load(handle)
    stations = bundle['stations'] if isinstance(bundle, dict) else bundle

    provinces = collections.defaultdict(list)
    districts = collections.defaultdict(list)
    neighbourhoods = collections.defaultdict(list)

    for station in stations:
        address = station.get('address')
        if not address:
            continue
        parts = [norm(p) for p in address.split(',')]
        parts = [p for p in parts if p]
        if len(parts) < 2:
            continue
        point = (float(station['lat']), float(station['lon']))

        province = parts[-1]
        district = parts[-2]
        if looks_like_street(province) or looks_like_street(district):
            continue

        provinces[province].append(point)
        districts[(district, province)].append(point)

        if len(parts) >= 5:
            neighbourhood = parts[0]
            if not looks_like_street(neighbourhood):
                neighbourhoods[(neighbourhood, district, province)].append(point)

    def centre(points):
        return (
            round(sum(p[0] for p in points) / len(points), 6),
            round(sum(p[1] for p in points) / len(points), 6),
        )

    places = []
    for name, points in provinces.items():
        lat, lon = centre(points)
        places.append({'name': name, 'detail': 'il', 'kind': 'province',
                       'lat': lat, 'lon': lon, 'count': len(points)})
    for (name, province), points in districts.items():
        if name == province:
            continue
        lat, lon = centre(points)
        places.append({'name': name, 'detail': province, 'kind': 'district',
                       'lat': lat, 'lon': lon, 'count': len(points)})
    for (name, district, province), points in neighbourhoods.items():
        if len(points) < MIN_STATIONS_FOR_NEIGHBOURHOOD:
            continue
        if name in (district, province):
            continue
        lat, lon = centre(points)
        places.append({'name': name, 'detail': '%s, %s' % (district, province),
                       'kind': 'neighbourhood', 'lat': lat, 'lon': lon, 'count': len(points)})

    # The search key is stored rather than computed on the phone: it is the same answer
    # every time and folding 6,000 names on every keystroke is work for nothing.
    for place in places:
        place['key'] = fold(place['name'])

    places.sort(key=lambda p: (-p['count'], p['name']))

    counts = collections.Counter(p['kind'] for p in places)
    print('yerler: %d  %s' % (len(places), dict(counts)))
    print('\nen cok istasyonu olanlar:')
    for place in places[:12]:
        print('  %-22s %-24s %4d' % (place['name'][:22], place['detail'][:24], place['count']))

    with io.open(OUT, 'w', encoding='utf-8', newline='') as handle:
        json.dump({'places': places}, handle, ensure_ascii=False, separators=(',', ':'))
    print('\nyazildi: %s' % OUT)


if __name__ == '__main__':
    main()
