#!/usr/bin/env bash
#
# Picks somewhere the app will actually be polling from, right now.
#
# PollingPolicy plans from the sky rather than from the interval in the
# settings: it sleeps until dark while the sun is up, and backs off under heavy
# cloud. So a harness that wants to watch the app wake on a schedule has to
# borrow a position where there is something to watch for - which, from Britain
# in the morning, is somewhere else entirely.
#
# Reads the sites in tools/observers.txt, keeps the ones that are dark now and
# stay dark for the whole window asked for, asks Open-Meteo what the cloud is
# doing over each, and prints the clearest. That is the app's own pair of gates
# - Twilight.CIVIL_DEGREES and PollingPolicy.HEAVY_CLOUD - applied to the list
# ahead of time, so the app lands on QUIET_MINUTES rather than DAYLIGHT or
# OVERCAST_MINUTES.
#
# Prints one tab-separated line and nothing else:
#
#   latitude<TAB>longitude<TAB>sun altitude<TAB>cloud per cent<TAB>name
#
# Exits 1 when no site qualifies, 2 on a usage error.
#
# Usage: tools/pick-observer.sh [--dark-for N] [--at EPOCH] [--list FILE]
#
#   --dark-for N  how many minutes the site must stay dark for (default 120).
#                 A site whose dawn arrives mid-run is worse than useless: the
#                 app re-plans to DAYLIGHT part way through and the gap that
#                 comes out reads exactly like Doze deferring a wake.
#   --at EPOCH    pick for a given instant rather than now. For testing, and
#                 for asking what this would have chosen at three this morning.
#                 Darkness is computed for that instant; the cloud reading is
#                 still the current one, because Open-Meteo is asked for what is
#                 overhead now. So --at answers "which of these is dark then",
#                 not "what would this have picked then".
#   --list FILE   a different site list (default tools/observers.txt)
#   --timeout N   seconds to allow Open-Meteo (default 20). On a timeout the
#                 darkest qualifying site is printed with an unknown cloud
#                 reading rather than nothing at all - the app treats unknown
#                 cloud as worth watching, so the choice is still a sound one.
#
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
LIST="$HERE/observers.txt"
DARK_FOR=120
AT=""
TIMEOUT=20

# Matches the app: Twilight.CIVIL_DEGREES, the threshold every poll gate is
# built on, and PollingPolicy.HEAVY_CLOUD, above which it stops watching.
CIVIL_DEGREES=-6
HEAVY_CLOUD=90

while [ $# -gt 0 ]; do
    case "$1" in
        --dark-for) DARK_FOR="$2"; shift 2 ;;
        --at) AT="$2"; shift 2 ;;
        --list) LIST="$2"; shift 2 ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        -h|--help) sed -e '1d' -e '/^[^#]/,$d' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -f "$LIST" ] || { echo "no site list at $LIST" >&2; exit 2; }
NOW=${AT:-$(date +%s)}

# Solar position, NOAA's low-precision algorithm, good to about 0.01 degrees -
# three orders of magnitude finer than the -6 threshold it is compared against.
# Geometric, with no refraction term, which is the same convention the civil
# twilight threshold itself uses.
#
# Checked against Open-Meteo's own sunrise and sunset across both hemispheres
# and the full range of longitudes: at each of those instants this returns
# -0.833 give or take 0.07, which is the refraction-and-semi-diameter offset
# standard rise/set is defined at. That is an independent authority for the same
# quantity, and the one the app already trusts for weather.
read -r -d '' SUN_AWK <<'AWK' || true
function d2r(d) { return d * 0.017453292519943295 }
function r2d(r) { return r * 57.29577951308232 }
function asin_(x) {
    if (x >= 1) return 1.5707963267948966
    if (x <= -1) return -1.5707963267948966
    return atan2(x, sqrt(1 - x * x))
}
function wrap(x, period,   y) { y = x - period * int(x / period); return (y < 0) ? y + period : y }
function sun_altitude(lat, lon, epoch,   n, L, g, lambda, eps, alpha, delta, gmst, H, sinalt) {
    n = epoch / 86400.0 + 2440587.5 - 2451545.0
    L = wrap(280.460 + 0.9856474 * n, 360)
    g = wrap(357.528 + 0.9856003 * n, 360)
    lambda = L + 1.915 * sin(d2r(g)) + 0.020 * sin(d2r(2 * g))
    eps = 23.439 - 0.0000004 * n
    alpha = r2d(atan2(cos(d2r(eps)) * sin(d2r(lambda)), cos(d2r(lambda))))
    delta = r2d(asin_(sin(d2r(eps)) * sin(d2r(lambda))))
    gmst = wrap(18.697374558 + 24.06570982441908 * n, 24)
    H = wrap(gmst * 15 + lon - alpha, 360)
    if (H > 180) H -= 360
    sinalt = sin(d2r(lat)) * sin(d2r(delta)) + cos(d2r(lat)) * cos(d2r(delta)) * cos(d2r(H))
    return r2d(asin_(sinalt))
}
AWK

# Dark now and still dark at the far end of the window. Both ends are checked
# rather than just the start, and the sun is sampled across the window rather
# than only at its edges - at high latitude in the right season it can dip below
# the threshold and back out again inside a couple of hours.
candidates=$(awk -v now="$NOW" -v span="$((DARK_FOR * 60))" -v limit="$CIVIL_DEGREES" \
    "$SUN_AWK"'
    /^#/ { next }
    NF < 3 { next }
    {
        lat = $1 + 0; lon = $2 + 0
        name = $0
        sub(/^[ \t]*[^ \t]+[ \t]+[^ \t]+[ \t]+/, "", name)
        worst = 999
        for (i = 0; i <= 12; i++) {
            alt = sun_altitude(lat, lon, now + span * i / 12)
            if (alt > worst || worst == 999) worst = alt
        }
        if (worst >= limit) next
        printf "%s\t%s\t%.1f\t%s\n", $1, $2, sun_altitude(lat, lon, now), name
    }' "$LIST" | sort -t "$(printf '\t')" -k3,3n)

if [ -z "$candidates" ]; then
    echo "no site in $LIST is dark for the next ${DARK_FOR} minutes" >&2
    exit 1
fi

# One request for every candidate: Open-Meteo takes parallel lists and answers
# in the order given. Asked for the same field the app asks for, from the same
# API, so the number here is the number the app's own cloud gate will see.
lats=$(printf '%s\n' "$candidates" | cut -f1 | paste -sd,)
lons=$(printf '%s\n' "$candidates" | cut -f2 | paste -sd,)
url="https://api.open-meteo.com/v1/forecast?latitude=${lats}&longitude=${lons}&current=cloud_cover"

cloud=""
if body=$(curl -sS --max-time "$TIMEOUT" "$url" 2>/dev/null); then
    # Parsed, not pattern-matched. `current_units` carries the same key name, so
    # a grep for cloud_cover reads the units block as a reading and silently
    # shifts every site's weather onto its neighbour.
    cloud=$(printf '%s' "$body" | node -e '
        let raw = "";
        process.stdin.on("data", d => raw += d);
        process.stdin.on("end", () => {
            try {
                const parsed = JSON.parse(raw);
                const places = Array.isArray(parsed) ? parsed : [parsed];
                console.log(places.map(p =>
                    p && p.current && Number.isFinite(p.current.cloud_cover)
                        ? p.current.cloud_cover : "").join("\n"));
            } catch { process.exit(1); }
        });' 2>/dev/null) || cloud=""
fi

if [ -z "$cloud" ]; then
    echo "could not reach Open-Meteo; choosing on darkness alone" >&2
    printf '%s\n' "$candidates" | head -1 | awk -F'\t' '{printf "%s\t%s\t%s\t?\t%s\n", $1, $2, $3, $4}'
    exit 0
fi

# Clearest first, and among equals the one deepest into its night.
printf '%s\n' "$candidates" | paste -d'\t' - <(printf '%s\n' "$cloud") \
    | awk -F'\t' -v heavy="$HEAVY_CLOUD" '
        $5 == "" { next }
        $5 + 0 >= heavy { next }
        { printf "%s\t%s\t%s\t%s\t%s\n", $1, $2, $3, $5, $4 }' \
    | sort -t "$(printf '\t')" -k4,4n -k3,3n | head -1 > /tmp/.pick.$$ || true

if [ ! -s /tmp/.pick.$$ ]; then
    rm -f /tmp/.pick.$$
    echo "every dark site is under heavy cloud (>= ${HEAVY_CLOUD}%)" >&2
    exit 1
fi
cat /tmp/.pick.$$
rm -f /tmp/.pick.$$
