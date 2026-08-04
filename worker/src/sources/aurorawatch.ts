/**
 * Parser for the AuroraWatch UK v0.2 status document.
 *
 * AuroraWatch is a UK-only enhancement layer over the global OVATION
 * foundation, worth the complexity because it runs on real UK magnetometer
 * data rather than a planetary index. Use is non-commercial only, the
 * User-Agent must name the app, and the alert thresholds must not be adjusted.
 *
 * The document is a few hundred bytes with a fixed shape, and Workers ship no
 * XML parser, so this reads the two attributes it needs directly rather than
 * pulling in a dependency to walk a tree of one useful element.
 */

/** The alert levels AuroraWatch UK defines. Nothing else is accepted. */
export const AURORAWATCH_LEVELS = ["green", "yellow", "amber", "red"] as const;

export type AuroraWatchLevel = (typeof AURORAWATCH_LEVELS)[number];

export interface AuroraWatchStatus {
  status: AuroraWatchLevel;
  updated: string;
  siteId: string;
}

function attribute(xml: string, element: string, name: string): string | null {
  const tag = new RegExp(`<${element}\\b[^>]*>`).exec(xml);
  if (tag === null) {
    return null;
  }
  const match = new RegExp(`\\b${name}\\s*=\\s*"([^"]*)"`).exec(tag[0]);
  return match?.[1] ?? null;
}

export function parseAuroraWatchStatus(xml: string): AuroraWatchStatus {
  const status = attribute(xml, "site_status", "status_id");
  if (status === null) {
    throw new Error("AuroraWatch document had no site_status element");
  }
  if (!(AURORAWATCH_LEVELS as readonly string[]).includes(status)) {
    throw new Error(`AuroraWatch returned an unrecognised status level: ${status}`);
  }

  const updated = /<datetime>([^<]*)<\/datetime>/.exec(xml)?.[1] ?? "";
  const siteId = attribute(xml, "site_status", "site_id") ?? "";

  return { status: status as AuroraWatchLevel, updated, siteId };
}
