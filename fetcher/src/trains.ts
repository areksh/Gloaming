import type { ParsedSatellite } from "./celestrak";

/**
 * Finding the Starlink launches that are still a train.
 *
 * The geometry is not the work here; the design calls it "fresh launch,
 * sunlit, low" and that is the whole prediction. The work is size. The Starlink
 * group runs to thousands of objects, and publishing it whole would cost every
 * device megabytes per fetch against the tens of kilobytes `sats.json` costs
 * now. So detection happens here and only the two ends of each train are
 * published: a description needs when the front of it rises and when the back
 * of it is clear, and nothing in between adds anything a person outside can
 * use.
 *
 * Three conditions, and all three are needed. Numerous, because a train is a
 * string of lights and four satellites is not. Low, because a launch that has
 * been raised to its operational shell is no longer bright or bunched. And
 * still gathered along one arc of the orbit, which is what makes it a train
 * rather than a plane of a constellation seen edge on.
 *
 * The bunching test is also how the feature expires. Nothing marks a train as
 * finished: it stops matching as the launch drifts apart, which is exactly when
 * there is no longer a train to look at.
 */

/** One launch still bunched together, as published in `sats.json`. */
export interface StarlinkTrain {
  /** The launch, without the piece letter: `2026-123`. */
  launch: string;
  /** How many objects of that launch are in the train. */
  members: number;
  /** Catalogue number of the satellite in front, which rises first. */
  lead: number;
  /** Catalogue number of the one at the back, which sets last. */
  tail: number;
}

/**
 * The fewest satellites worth calling a train.
 *
 * A Starlink launch deploys dozens; anything much smaller than this is either a
 * rideshare or a launch most of which has already dispersed, and in neither
 * case is there a string of lights to see.
 */
const MINIMUM_MEMBERS = 8;

/**
 * Revolutions per day above which a launch has not yet been raised.
 *
 * The operational shell at 550 km comes out around 15.06; a freshly deployed
 * batch sits nearer 15.9. This threshold falls between them, at roughly 440 km.
 */
const MINIMUM_MEAN_MOTION = 15.4;

/**
 * How far around the orbit the members may be strung and still be a train.
 *
 * Generous rather than tight. A train is visibly a train well after it has
 * stopped being a tight line, and the failure of being slightly too generous is
 * a description that says "a train of about 22" for something a little strung
 * out - which is what it looks like.
 */
const MAXIMUM_SPREAD_DEGREES = 45;

/** The launch part of an international designator, or null if it is not one. */
function launchOf(objectId: string | null): string | null {
  if (objectId === null) return null;
  const match = /^(\d{4}-\d{3})[A-Z]{1,3}$/.exec(objectId.trim().toUpperCase());
  return match === null ? null : (match[1] as string);
}

/**
 * The arc the members occupy, and their order along it.
 *
 * Mean anomaly wraps, so a train sitting across 360 degrees would look like the
 * widest possible spread if the numbers were simply sorted. The trick is that
 * the members occupy one arc and the *gap* is everything else: find the largest
 * gap between neighbours and the arc is what is left, read starting from the
 * far side of that gap.
 */
function alongTrack(members: ParsedSatellite[]): { spread: number; ordered: ParsedSatellite[] } {
  const sorted = [...members].sort((a, b) => a.mean_anomaly - b.mean_anomaly);

  let widest = 0;
  let after = 0;
  for (let i = 0; i < sorted.length; i++) {
    const here = (sorted[i] as ParsedSatellite).mean_anomaly;
    const next = (sorted[(i + 1) % sorted.length] as ParsedSatellite).mean_anomaly;
    const gap = i === sorted.length - 1 ? next + 360 - here : next - here;
    if (gap > widest) {
      widest = gap;
      after = (i + 1) % sorted.length;
    }
  }

  return {
    spread: 360 - widest,
    ordered: [...sorted.slice(after), ...sorted.slice(0, after)],
  };
}

/**
 * The launches among [satellites] that are still a train.
 *
 * Sorted by launch so the published document is stable between runs: an
 * identical sky must produce an identical file, or every device re-downloads
 * for nothing.
 */
export function detectTrains(satellites: ParsedSatellite[]): StarlinkTrain[] {
  const launches = new Map<string, ParsedSatellite[]>();
  for (const satellite of satellites) {
    const launch = launchOf(satellite.objectId);
    if (launch === null) continue;
    const members = launches.get(launch);
    if (members === undefined) launches.set(launch, [satellite]);
    else members.push(satellite);
  }

  const trains: StarlinkTrain[] = [];
  for (const [launch, members] of launches) {
    if (members.length < MINIMUM_MEMBERS) continue;
    if (!members.every((member) => member.mean_motion > MINIMUM_MEAN_MOTION)) continue;

    const { spread, ordered } = alongTrack(members);
    if (spread > MAXIMUM_SPREAD_DEGREES) continue;

    // Mean anomaly increases along the direction of travel, so the last of the
    // arc is the one in front: it clears the horizon first, and the first of
    // the arc is the one still coming when the rest have gone.
    trains.push({
      launch,
      members: members.length,
      lead: (ordered[ordered.length - 1] as ParsedSatellite).catalogue,
      tail: (ordered[0] as ParsedSatellite).catalogue,
    });
  }

  return trains.sort((a, b) => a.launch.localeCompare(b.launch));
}
