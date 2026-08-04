/**
 * Packing of the SWPC OVATION aurora grid.
 *
 * SWPC publishes a global 360 x 181 grid of aurora probability as roughly a
 * megabyte of JSON triples, which is far too much to push to devices every few
 * minutes. Probability is a percentage, so it fits in a byte, and the packed
 * grid is 65,160 bytes before compression - overwhelmingly zeros, so it gzips
 * to single-digit kilobytes.
 *
 * The device holds the whole grid and indexes it locally. That is a privacy
 * decision, not a performance one: a "probability at this coordinate" endpoint
 * would log an approximate user location on every poll.
 */

export const GRID_LONGITUDES = 360;
export const GRID_LATITUDES = 181;

/** Bytes in a packed grid: one per cell. */
export const OVAL_GRID_BYTES = GRID_LONGITUDES * GRID_LATITUDES;

/** The shape SWPC serves at `ovation_aurora_latest.json`. */
export interface OvationGrid {
  observationTime: string;
  forecastTime: string;
  /** `[longitude, latitude, probability]`, longitude-major. */
  coordinates: [number, number, number][];
}

/**
 * Byte offset of a cell in the packed grid.
 *
 * The layout is row-major with one row per latitude, running from -90 to +90,
 * each row holding longitudes 0 to 359. Note that this is a transpose of the
 * order SWPC serves, which is longitude-major.
 */
export function ovalIndex(lon: number, lat: number): number {
  return (lat + 90) * GRID_LONGITUDES + lon;
}

/** Reads one cell back out of a packed grid, as the device does. */
export function readOvalCell(packed: Uint8Array, lon: number, lat: number): number {
  const value = packed[ovalIndex(lon, lat)];
  if (value === undefined) {
    throw new Error(`Cell ${lon},${lat} is outside the packed grid`);
  }
  return value;
}

/**
 * Packs a parsed OVATION grid into one byte per cell.
 *
 * Throws rather than returning a partial grid. A grid that is short, repeats a
 * cell, or carries a junk probability would otherwise be published with the
 * missing cells left at zero, which reads as "no aurora anywhere" and silently
 * suppresses alerts - the one failure this app must not have.
 */
export function packOvalGrid(grid: OvationGrid): Uint8Array {
  const { coordinates } = grid;
  if (coordinates.length !== OVAL_GRID_BYTES) {
    throw new Error(
      `OVATION grid must cover the globe: expected ${OVAL_GRID_BYTES} cells, got ${coordinates.length}`,
    );
  }

  const packed = new Uint8Array(OVAL_GRID_BYTES);
  const seen = new Uint8Array(OVAL_GRID_BYTES);

  for (const [lon, lat, probability] of coordinates) {
    if (!Number.isInteger(lon) || lon < 0 || lon >= GRID_LONGITUDES) {
      throw new Error(`Longitude ${lon} is outside 0-${GRID_LONGITUDES - 1}`);
    }
    if (!Number.isInteger(lat) || lat < -90 || lat > 90) {
      throw new Error(`Latitude ${lat} is outside -90-90`);
    }
    if (!Number.isFinite(probability)) {
      throw new Error(`Cell ${lon},${lat} has a non-finite probability`);
    }

    const index = ovalIndex(lon, lat);
    if (seen[index] === 1) {
      throw new Error(`Cell ${lon},${lat} appears twice in the grid`);
    }
    seen[index] = 1;

    packed[index] = Math.min(100, Math.max(0, Math.round(probability)));
  }

  return packed;
}
