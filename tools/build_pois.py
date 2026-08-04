"""
Turns the fetched OSM amenities into the asset the app reads.

Two things happen here and both are about size. Fifty thousand rows written as objects
would repeat the words "lat", "lon", "kind" and "name" fifty thousand times, so each row
becomes a tuple. And the kinds become short codes, because "supermarket" spelled out
once per row is a hundred kilobytes of the same word.

Rows with no name are kept. A cafe with no name is still a cafe, and the dot is worth
drawing even when there is nothing to write beside it.
"""

import io
import json
import collections

RAW = 'data/pois_raw.json'
OUT = 'app/src/main/assets/pois_tr.json'

# Short, and stable: the drawing code groups them into four colours, so what matters is
# that the same kind always arrives as the same string.
CODES = {
    'cafe': 'cafe',
    'restaurant': 'rest',
    'fast_food': 'food',
    'bakery': 'bake',
    'fuel': 'fuel',
    'pharmacy': 'phar',
    'toilets': 'wc',
    'supermarket': 'mkt',
    'convenience': 'shop',
    'mall': 'mall',
}

# A name longer than this is a description, not a label, and will not fit on a map.
MAX_NAME = 28


def main():
    with io.open(RAW, encoding='utf-8') as handle:
        rows = json.load(handle)

    out = []
    kinds = collections.Counter()
    for row in rows:
        code = CODES.get(row.get('kind'))
        if not code:
            continue
        name = (row.get('name') or '').strip()
        if len(name) > MAX_NAME:
            name = ''
        out.append([row['lat'], row['lon'], code, name])
        kinds[code] += 1

    with io.open(OUT, 'w', encoding='utf-8', newline='') as handle:
        json.dump(out, handle, ensure_ascii=False, separators=(',', ':'))

    named = sum(1 for r in out if r[3])
    print('POI: %d  (adi olan %d)' % (len(out), named))
    print('turler:', dict(kinds.most_common()))
    import os
    print('%s  %.1f MB' % (OUT, os.path.getsize(OUT) / 1e6))


if __name__ == '__main__':
    main()
