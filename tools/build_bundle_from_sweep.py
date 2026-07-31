"""
Turns a raw TomTom sweep into the charging-station bundle that ships in the APK.

`sweep_tomtom.py` writes TomTom's answers back verbatim — 21 MB of POI records with
viewports, scores, classifications and category ids that the app never reads. This
converts them into the same normalised shape `ChargerSeeder` already parses, which
is about a fifth of the size.

Why this became the bundle. The five-source merge in `build_charger_bundle.py` found
6,102 stations and could state the power on under a third of them, because the only
source that reliably carries kW is Open Charge Map and the operators' own feeds do
not. The sweep found 16,104, and states power, connector types and an address on
every single one — including the networks whose own lists are unreachable and which
the merge could only see third-hand: Voltrun, WAT Mobilite, Eşarj, Otowatt, Sharz.
The merged bundle is kept at `data/chargers_tr.merged.json` as a fallback.

The mapping is deliberately identical to `TomTomChargerSource.query`, so a station
the user later re-fetches with their own key lands on the same row rather than
appearing twice: same `sourceId`, same `source`, same fields read the same way.

Co-located records are left alone. TomTom lists a large site as several POIs, but
`groupIntoSites` already collapses anything within 60 m at display time, so dedup
here would only take the socket counts away from it.

Usage:  python tools/build_bundle_from_sweep.py [sweep.json]
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_charger_bundle import OPERATOR_ALIASES, ascii_fold

IN = sys.argv[1] if len(sys.argv) > 1 else "sarjistasyonlari_tr.json"
OUT = "app/src/main/assets/chargers_tr.json"

#: Above this, a connector count is not a socket count — see build_charger_bundle.
PLAUSIBLE_MAX_CHARGE_POINTS = 50


def operator(raw):
    """
    TomTom's brand name, canonicalised only where we know the network.

    Not `fold_operator`: that exists to reconcile one network spelled four ways
    across five sources, and its tidy-up pass title-cases whatever it does not
    recognise. Here there is one source with one spelling per brand, and running the
    full fold over it turned K-ŞARJ into K-Şarj and MKS-ŞARJ into Mks-ŞARJ while
    merging only 10 of 623 names. The alias table still earns its place — it is what
    turns TomTom's "En Yakit" into En Yakıt — so the keyword match is kept and the
    tidy-up is dropped.
    """
    if not raw:
        return None
    folded = ascii_fold(raw)
    for keyword, display in OPERATOR_ALIASES:
        if keyword in folded:
            return display
    return raw.strip() or None


def convert(record):
    position = record.get("position") or {}
    lat, lon = position.get("lat"), position.get("lon")
    if lat is None or lon is None:
        return None
    tomtom_id = record.get("id")
    if not tomtom_id:
        return None

    poi = record.get("poi") or {}
    name = (poi.get("name") or "").strip() or None
    brand = ((poi.get("brands") or [{}])[0].get("name") or "").strip() or None

    types, max_kw, saw_dc, saw_ac = [], None, False, False
    connectors = (record.get("chargingPark") or {}).get("connectors") or []
    for c in connectors:
        kind = (c.get("connectorType") or "").strip()
        if kind and kind not in types:
            types.append(kind)
        kw = c.get("ratedPowerKW")
        if isinstance(kw, (int, float)) and kw > 0:
            max_kw = kw if max_kw is None else max(max_kw, kw)
        # "DC", "AC1", "AC3" — the prefix is the part that matters.
        current = (c.get("currentType") or "").upper()
        if current.startswith("DC"):
            saw_dc = True
        elif current.startswith("AC"):
            saw_ac = True

    sockets = len(connectors)
    return {
        "sourceId": f"tomtom:{tomtom_id}",
        "source": "tomtom",
        "name": name,
        # Brand first, as the runtime source does: it is the network, while `name`
        # is often the host — "Migros Ataşehir" rather than whose charger it is.
        "operator": operator(brand) or operator(name),
        "lat": lat,
        "lon": lon,
        "connectors": ", ".join(types) or None,
        "maxPowerKw": max_kw,
        "isDc": True if saw_dc else (False if saw_ac else None),
        "address": (record.get("address") or {}).get("freeformAddress") or None,
        "chargePoints": sockets if 1 <= sockets <= PLAUSIBLE_MAX_CHARGE_POINTS else None,
    }


def main():
    try:
        with open(IN, encoding="utf-8") as handle:
            raw = json.load(handle)["stations"]
    except FileNotFoundError:
        print(f"ABORT: {IN} bulunamadı. Önce tools/sweep_tomtom.py çalıştır.")
        return 1

    stations = [s for s in (convert(r) for r in raw) if s]

    # An empty or barely-populated result means the sweep file is broken, not that
    # Türkiye lost its chargers. Writing it out would ship an empty map, and nothing
    # downstream could tell that apart from a good build.
    if len(stations) < len(raw) * 0.9 or not stations:
        print(f"ABORT: {len(raw)} kayıttan yalnızca {len(stations)} tanesi "
              f"dönüştürülebildi. {OUT} değiştirilmedi.")
        return 1

    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump({"stations": stations}, handle, ensure_ascii=False,
                  separators=(",", ":"))

    powered = sum(1 for s in stations if s["maxPowerKw"])
    operators = len({s["operator"] for s in stations if s["operator"]})
    print(f"okundu      : {IN}  ({len(raw)} kayıt)")
    print(f"istasyon    : {len(stations)}")
    print(f"gücü bilinen: {powered} (%{100 * powered // len(stations)})")
    print(f"marka       : {operators}")
    print(f"yazıldı     : {OUT}  {os.path.getsize(OUT) / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
