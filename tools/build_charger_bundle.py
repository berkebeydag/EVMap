"""
Builds the charging-station dataset that ships inside the APK.

Merges four sources, because no single one covers Türkiye:

  Open Charge Map EV-specific and by far the best attributed (power on ~94%), ~2100
                 stations. Needs a free key, passed in via OCM_API_KEY.
  ZES            the largest operator's own list, ~2000 stations in Türkiye, from
                 the endpoint behind the map on their site. No key, one request.
                 Gives socket counts split AC/DC/HPC but never a kW figure.
  İBB open data  the İstanbul slice of EPDK's official register, ~2000 public rows.
                 İstanbul only, but that is where most of the fleet is.
  OpenStreetMap  free and nationwide, but measured at ~760 stations and power
                 recorded on under 10% — a base layer, not a picture.

Deduplicated by real distance, first source wins. Measured union: about 5,600.

The other big networks were checked and are not reachable: Eşarj and Otowatt sit
behind a WAF that rejects anything but a real browser session, Voltrun behind a
Vercel challenge, and Sharz and Trugo publish no station data on the web at all —
theirs lives only in their phone apps. Whatever they hold shows up here only to the
extent Open Charge Map's contributors have entered it.

Usage:  OCM_API_KEY=... python tools/build_charger_bundle.py
"""
import json
import math
import os
import sys
import urllib.parse
import urllib.request

OUT = "app/src/main/assets/chargers_tr.json"
UA = {"User-Agent": "IoniqScope/0.1 (personal EV app)"}
TURKEY_AREA = 3600174737


def get(url, headers=None, timeout=300):
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    return urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8")


def post(url, data, timeout=300):
    req = urllib.request.Request(url, data=data.encode(), headers=UA)
    return urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8")


# --------------------------------------------------------------- OpenStreetMap

DC_HINTS = ("combo", "ccs", "chademo", "tesla_supercharger")
AC_HINTS = ("type2", "schuko", "type1")
OPERATORS = ["SHARZ", "ZES", "ESARJ", "VOLTRUN", "TRUGO", "TESLA", "ASTOR",
             "TOGG", "BORENCO", "WAT MOBILITE", "BEEFULL", "OTOWATT", "OTOPRIZ"]


def fold_operator(raw):
    """OSM spells the same operator several ways; fold the common Turkish ones."""
    if not raw:
        return None
    upper = raw.upper()
    for a, b in [("İ", "I"), ("Ş", "S"), ("Ğ", "G"), ("Ü", "U"), ("Ö", "O"), ("Ç", "C")]:
        upper = upper.replace(a, b)
    for known in OPERATORS:
        if known in upper:
            return known
    return raw.strip()


def parse_power(text):
    if not text:
        return None
    lowered = str(text).lower()
    number = ""
    for ch in lowered:
        if ch.isdigit() or ch == ".":
            number += ch
        elif number:
            break
    if not number:
        return None
    try:
        value = float(number)
    except ValueError:
        return None
    if "kw" in lowered:
        pass
    elif "w" in lowered:
        value /= 1000.0
    return value if 0 < value < 1000 else None


#: Above this, a "socket count" is not believable and is discarded as untagged.
#: Measured against OCM and the EPDK register together, the largest genuine public
#: site in Türkiye has 28 charge points. OSM values of 150, 180 and 300 turned up
#: instead, which are charger power ratings in kW put in the wrong field — and a
#: single mistagged node was enough to poison the count of a real station nearby,
#: because the merge takes the highest stated figure it sees.
PLAUSIBLE_MAX_CHARGE_POINTS = 50


def osm_capacity(tags):
    """OSM's own socket count, where a mapper filled it in and it is believable."""
    raw = str(tags.get("capacity", ""))
    if not raw.isdigit():
        return None
    value = int(raw)
    return value if 0 < value <= PLAUSIBLE_MAX_CHARGE_POINTS else None


def fetch_osm():
    query = (
        f"[out:json][timeout:180];area({TURKEY_AREA})->.tr;("
        'nwr["amenity"="charging_station"](area.tr);'
        'nwr["man_made"="charge_point"](area.tr);'
        ");out tags center;"
    )
    body = urllib.parse.urlencode({"data": query})
    for endpoint in ("https://overpass-api.de/api/interpreter",
                     "https://overpass.kumi.systems/api/interpreter"):
        try:
            raw = post(endpoint, body)
            if raw.lstrip().startswith("{"):
                break
        except Exception as exc:
            print(f"  overpass {endpoint} failed: {exc}")
    else:
        return []

    out = []
    for element in json.loads(raw)["elements"]:
        tags = element.get("tags") or {}
        centre = element.get("center") or element
        lat, lon = centre.get("lat"), centre.get("lon")
        if lat is None or lon is None:
            continue

        sockets = [k.removeprefix("socket:") for k, v in tags.items()
                   if k.startswith("socket:") and not k.endswith(":output")
                   and str(v).lower() != "no"]
        powers = [parse_power(v) for k, v in tags.items()
                  if k in ("charge", "maxpower") or k.endswith(":output")]
        powers = [p for p in powers if p]

        is_dc = None
        if any(h in k for k in tags for h in DC_HINTS):
            is_dc = True
        elif any(h in k for k in tags for h in AC_HINTS):
            is_dc = False

        address = " ".join(filter(None, (
            tags.get("addr:street"), tags.get("addr:housenumber"),
            tags.get("addr:district"), tags.get("addr:city"))))

        out.append({
            "sourceId": f"osm:{element.get('type','node')}:{element.get('id')}",
            "source": "osm",
            "name": tags.get("name"),
            "operator": fold_operator(tags.get("operator") or tags.get("brand")
                                      or tags.get("network")),
            "lat": lat, "lon": lon,
            "connectors": ", ".join(sorted(set(sockets))) or None,
            "maxPowerKw": max(powers) if powers else None,
            "isDc": is_dc,
            "address": address or None,
            "_stated": osm_capacity(tags),
        })
    return out


# -------------------------------------------------------------- Open Charge Map

OCM_DC = 30


def fetch_ocm(key):
    url = ("https://api.openchargemap.io/v3/poi/?output=json&countrycode=TR"
           "&maxresults=100000")
    data = json.loads(get(url, {"X-API-Key": key}))
    out = []
    for poi in data:
        info = poi.get("AddressInfo") or {}
        lat, lon = info.get("Latitude"), info.get("Longitude")
        if lat is None or lon is None:
            continue

        connections = poi.get("Connections") or []
        names, powers, is_dc, sockets = set(), [], None, 0
        for c in connections:
            title = (c.get("ConnectionType") or {}).get("Title")
            if title:
                names.add(title)
            if c.get("PowerKW"):
                powers.append(c["PowerKW"])
            current = (c.get("CurrentType") or {}).get("ID")
            if current == OCM_DC:
                is_dc = True
            elif current and is_dc is None:
                is_dc = False
            # Quantity is how many identical sockets that connection stands for.
            sockets += c.get("Quantity") or 1

        out.append({
            "sourceId": f"ocm:{poi.get('ID')}",
            "source": "ocm",
            "name": info.get("Title"),
            "operator": (poi.get("OperatorInfo") or {}).get("Title"),
            "lat": lat, "lon": lon,
            "connectors": ", ".join(sorted(names)) or None,
            "maxPowerKw": max(powers) if powers else None,
            "isDc": is_dc,
            "address": " ".join(filter(None, (
                info.get("AddressLine1"), info.get("Town"),
                info.get("StateOrProvince")))) or None,
            "_stated": sockets or None,
        })
    return out


# -------------------------------------------------------------------------- ZES

ZES_MARKERS = "https://zes.net/Station/MarkerSet"

#: Roughly Türkiye, used only to drop roaming partners ZES lists abroad.
TR_BOX = (35.8, 42.2, 25.5, 45.0)


def fetch_zes():
    """
    ZES's own station list, the one behind the map on their site.

    The largest network in the country by a wide margin, and the single biggest
    thing missing from the other three sources: measured, OpenStreetMap knows about
    a small fraction of it. One request returns everything, with no key and no
    pagination.

    Power is deliberately left unknown. The feed gives connector counts split into
    AC, DC and HPC but never a kW figure, and turning "this site has an HPC socket"
    into a number would be inventing one.
    """
    data = json.loads(get(ZES_MARKERS, {"User-Agent": "Mozilla/5.0"}))
    out = []
    for s in data:
        # Their Greek and Bulgarian roaming partners come down the same feed.
        if not s.get("isZesStation"):
            continue
        lat, lon = s.get("latitude"), s.get("longitude")
        if lat is None or lon is None:
            continue
        if not (TR_BOX[0] < lat < TR_BOX[1] and TR_BOX[2] < lon < TR_BOX[3]):
            continue

        ac = s.get("acConnectorCount") or 0
        dc = s.get("dcConnectorCount") or 0
        hpc = s.get("hpcConnectorCount") or 0
        sockets = ac + dc + hpc
        if sockets == 0:
            continue

        out.append({
            "sourceId": f"zes:{s.get('id')}",
            "source": "zes",
            "name": (s.get("name") or "").strip() or None,
            "operator": "ZES",
            "lat": lat, "lon": lon,
            "connectors": None,
            "maxPowerKw": None,
            "isDc": True if (dc or hpc) else False,
            "address": None,
            "_stated": sockets,
        })
    return out


# ------------------------------------------------------------------- İBB / EPDK

IBB_GEOJSON = ("https://data.ibb.gov.tr/dataset/79b0e26e-e923-498b-a675-453382274178"
               "/resource/726e9d82-37f7-4142-8fa0-4f70a5530188/download/"
               "sarj_istasyonlari.geojson")


def fetch_ibb():
    data = json.loads(get(IBB_GEOJSON))
    out = []
    for feature in data.get("features", []):
        geometry = feature.get("geometry") or {}
        if geometry.get("type") != "Point":
            continue
        lon, lat = geometry["coordinates"][:2]
        props = feature.get("properties") or {}

        # HIZMET_SEKLI splits the licence register into HALKA_ACIK (open to the
        # public) and OZEL (private). The 904 private rows are the wallboxes inside
        # residential car parks — real, licensed, and useless to a driver, who
        # cannot get through the gate. They are also what made a single site appear
        # to have 300 sockets. A map that sends someone at 5% to an apartment
        # garage is worse than one that shows fewer chargers.
        if props.get("HIZMET_SEKLI") == "OZEL":
            continue
        out.append({
            "sourceId": f"ibb:{props.get('ISTASYON_NO')}",
            "source": "ibb",
            "name": (props.get("AD") or "").strip() or None,
            "operator": fold_operator(props.get("AGIL_ISLETMECISI_UNVAN")),
            "lat": lat, "lon": lon,
            # The socket detail lives in a separate file keyed by station; the map
            # only needs where to go, so it is deliberately not merged in here.
            "connectors": None,
            "maxPowerKw": None,
            "isDc": None,
            "address": (props.get("ADRES") or "").strip() or None,
            # One row is one socket in this feed, so the merge does the counting.
            "_stated": None,
        })
    return out


# ------------------------------------------------------------------------ merge

def merge(*groups, radius_m=50.0):
    """
    First source wins; a later station within [radius_m] is treated as the same place.

    Real distance rather than grid-cell adjacency: a cell test with a neighbour
    sweep silently merges anything up to about twice the cell size, which at these
    latitudes was folding together stations 100 m apart on opposite sides of a car
    park. The grid is kept only as an index so this stays linear.

    A duplicate is folded into the record that is kept rather than discarded: OSM
    models each socket at a site as its own node, so the records being collapsed
    here are usually the answer to "how many sockets are there". Losing them would
    leave the app with nothing to show when a station is tapped.
    """
    index, out = {}, []
    cell = radius_m / 111_320.0  # degrees, latitude-equivalent

    for group in groups:
        for station in group:
            lat, lon = station["lat"], station["lon"]
            gx, gy = math.floor(lat / cell), math.floor(lon / cell)

            match = None
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for other in index.get((gx + dx, gy + dy), ()):
                        dlat = (lat - other["lat"]) * 111_320.0
                        dlon = (lon - other["lon"]) * 111_320.0 * math.cos(math.radians(lat))
                        if math.hypot(dlat, dlon) < radius_m:
                            match = other
                            break
                    if match:
                        break
                if match:
                    break

            if match is not None:
                match["_merged"] += 1
                stated = station.get("_stated")
                if stated and stated > (match.get("_stated") or 0):
                    match["_stated"] = stated
                # A duplicate often knows something the winner does not.
                for field in ("maxPowerKw", "connectors", "address", "operator", "name"):
                    if match.get(field) is None:
                        match[field] = station.get(field)
                if station.get("isDc") is True:
                    match["isDc"] = True
                continue

            station["_merged"] = 1
            index.setdefault((gx, gy), []).append(station)
            out.append(station)

    for station in out:
        # Only ever reports a count it actually has evidence for: either the source
        # stated one, or this many separate records were seen at the spot. A lone
        # record that said nothing stays null, not 1.
        stated = station.pop("_stated", None) or 0
        merged = station.pop("_merged", 1)
        station["chargePoints"] = max(stated, merged if merged > 1 else 0) or None

    return out


def main():
    key = os.environ.get("OCM_API_KEY", "").strip()

    print("OpenStreetMap…")
    osm = fetch_osm()
    print(f"  {len(osm)}")

    ocm = []
    if key:
        print("Open Charge Map…")
        ocm = fetch_ocm(key)
        print(f"  {len(ocm)}")
    else:
        print("Open Charge Map skipped (set OCM_API_KEY to include it)")

    print("ZES…")
    zes = fetch_zes()
    print(f"  {len(zes)}")

    print("İBB / EPDK (İstanbul)…")
    ibb = fetch_ibb()
    print(f"  {len(ibb)}")

    # A source that returned nothing has failed, not shrunk to zero — Overpass in
    # particular answers 504 under load. Writing the bundle anyway would quietly
    # ship a file missing several hundred stations, and nothing downstream could
    # tell that apart from a good build.
    missing = [name for name, group in (("OpenStreetMap", osm),
                                        ("Open Charge Map", ocm if key else None),
                                        ("ZES", zes),
                                        ("İBB", ibb)) if group is not None and not group]
    if missing:
        print(f"\nABORT: no data from {', '.join(missing)}. {OUT} left untouched.")
        return 1

    # Best-attributed source first, so its power and connector data survives dedup.
    # ZES sits behind Open Charge Map, which usually carries a kW figure for the same
    # site, but ahead of the rest: it is the operator's own list and its socket counts
    # are authoritative where nobody else states one.
    merged = merge(ocm, zes, ibb, osm)
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump({"stations": merged}, handle, ensure_ascii=False,
                  separators=(",", ":"))

    by_source = {}
    for station in merged:
        by_source[station["source"]] = by_source.get(station["source"], 0) + 1
    print(f"\nmerged: {len(merged)}  {by_source}")
    print(f"written: {OUT}  {os.path.getsize(OUT)/1024:.0f} KB")


if __name__ == "__main__":
    sys.exit(main())
