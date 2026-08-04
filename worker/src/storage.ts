/**
 * Publishing to R2.
 *
 * The Worker is never in the read path: it writes objects and the CDN serves
 * them directly. That only works if `Cache-Control` is set at PUT time, so
 * these values are part of the contract rather than a tuning detail. Without a
 * `max-age` the edge does not absorb the fan-out and every device revalidates
 * against R2 on every poll.
 */

/** The documented caching contract, one entry per published object. */
export const CACHE_CONTROL = {
  "index.json": "public, max-age=3600",
  "status.json": "public, max-age=60, stale-while-revalidate=120",
  "oval.bin": "public, max-age=120, stale-while-revalidate=180",
  "sats.json": "public, max-age=1800",
  "comets.json": "public, max-age=21600",
} as const;

export interface PublishOptions {
  cacheControl: string;
  contentType: string;
}

export async function publish(
  bucket: R2Bucket,
  key: string,
  body: string | ArrayBuffer | ArrayBufferView,
  options: PublishOptions,
): Promise<R2Object> {
  return bucket.put(key, body as ArrayBuffer | string, {
    httpMetadata: {
      cacheControl: options.cacheControl,
      contentType: options.contentType,
    },
  });
}

/**
 * Compresses with gzip so the ~65 KB grid ships as a couple of kilobytes.
 *
 * Deliberately no `Content-Encoding` accompanies the result. Declaring one
 * invites the CDN to inflate the object and cache the inflated form, which it
 * then serves in full because it does not re-compress `application/octet-stream`
 * - measured in production as 65,160 bytes delivered against 1,763 stored, and
 * a weak ETag that undermines the conditional GET as well. Treating compression
 * as part of the payload rather than as a transfer encoding leaves nothing for
 * an intermediary to helpfully undo; the client inflates it.
 */
export async function gzip(body: Uint8Array): Promise<ArrayBuffer> {
  const stream = new Response(body as unknown as BodyInit).body?.pipeThrough(
    new CompressionStream("gzip"),
  );
  if (stream === undefined) {
    throw new Error("Failed to open a compression stream");
  }
  return new Response(stream).arrayBuffer();
}
