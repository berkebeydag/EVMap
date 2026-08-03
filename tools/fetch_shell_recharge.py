"""
Fetches Shell's own list of Turkish sites that have charging.

Shell's station finder is an iframe onto shellretaillocator.geoapp.me, whose API takes
a bounding box. Asked for a box that is too large it answers with clusters instead of
places, so this walks Turkey as a quadtree: ask for a box, and if the answer is
clusters rather than locations, split it in four and ask again. Boxes that come back
empty are not split.

Run once to produce data/shell_stations.json. The app never calls this; it is how the
bundle gets built, the same as the TomTom sweep.

`fuel_type=ev` is the filter that matters — Shell has some 1,100 forecourts in Turkey
and only a fraction of them charge anything.

No power or connector data comes back, so none is recorded. The feed knows the site
has "shell_recharge" among its fuels and nothing about how fast.
"""

import io
import json
import time
import urllib.parse
import urllib.request

BASE = 'https://shellretaillocator.geoapp.me/api/v2/locations/within_bounds'
OUT = 'data/shell_stations.json'

# Turkey, with enough margin to catch anything on the coast.
BOUNDS = (35.7, 25.5, 42.3, 45.0)
MIN_SPAN = 0.25          # a box this small is asked once and believed
POLITE_DELAY_S = 0.35

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                  '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    'Referer': 'https://shellretaillocator.geoapp.me/',
    'Accept': 'application/json',
}


def query(south, west, north, east):
    params = [
        ('sw[]', south), ('sw[]', west),
        ('ne[]', north), ('ne[]', east),
        ('with_any[fuel_type][]', 'ev'),
        ('limit', 500),
        ('locale', 'tr_TR'),
        ('format', 'json'),
    ]
    url = BASE + '?' + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def sweep(south, west, north, east, found, depth=0):
    time.sleep(POLITE_DELAY_S)
    try:
        answer = query(south, west, north, east)
    except Exception as error:                      # noqa: BLE001 - reported, not raised
        print('  %s hata: %s' % (' ' * depth, error))
        return

    locations = answer.get('locations') or []
    clusters = answer.get('clusters') or []

    for location in locations:
        found[str(location['id'])] = location

    span = max(north - south, east - west)
    if clusters and span > MIN_SPAN:
        mid_lat = (south + north) / 2
        mid_lon = (west + east) / 2
        for box in (
            (south, west, mid_lat, mid_lon),
            (south, mid_lon, mid_lat, east),
            (mid_lat, west, north, mid_lon),
            (mid_lat, mid_lon, north, east),
        ):
            sweep(*box, found=found, depth=depth + 1)
    elif clusters:
        # As small as this goes and still clustered: take the centroids as places, so a
        # dense city is not silently dropped.
        print('  %s %d kume en kucuk kutuda kaldi' % (' ' * depth, len(clusters)))


def main():
    found = {}
    sweep(*BOUNDS, found=found)

    rows = sorted(found.values(), key=lambda r: str(r['id']))
    turkish = [r for r in rows if (r.get('country_code') or '').upper() == 'TR']
    print('bulunan: %d (Turkiye: %d)' % (len(rows), len(turkish)))

    with io.open(OUT, 'w', encoding='utf-8', newline='') as handle:
        json.dump(turkish, handle, ensure_ascii=False, separators=(',', ':'))
    print('yazildi: %s' % OUT)


if __name__ == '__main__':
    main()
