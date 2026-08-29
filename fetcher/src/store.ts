import { AwsClient } from "aws4fetch";
import { explainFailure } from "./network";

/**
 * The handful of R2 operations this needs, over the S3-compatible API.
 *
 * An interface rather than a concrete client so the run logic can be tested
 * against an in-memory bucket. The Worker gets the same operations for free
 * from its binding; here they have to be signed and sent.
 */
export interface ObjectStore {
  get(key: string): Promise<string | null>;
  put(key: string, body: string, options?: PutOptions): Promise<void>;
}

export interface PutOptions {
  contentType?: string;
  /**
   * Set at PUT time, because the Worker is never in the read path and the CDN
   * serves these objects directly. Without it the edge does not absorb the
   * fan-out and every device revalidates against R2 on every poll.
   */
  cacheControl?: string;
}

export interface R2Credentials {
  accountId: string;
  accessKeyId: string;
  secretAccessKey: string;
  bucket: string;
  /**
   * How many times a failed R2 call is retried, with backoff.
   *
   * Set explicitly because the library's default is ten, which turns a
   * persistently unhappy bucket into a job that never ends. Retrying here does
   * not touch CelesTrak's no-retry rule: their endpoint is fetched with a plain
   * `fetch` through the breaker, and only R2 goes through this client.
   */
  retries?: number;
}

/**
 * R2 through its S3-compatible API.
 *
 * `aws4fetch` rather than the AWS SDK: SigV4 signing is the only thing needed
 * from it, and a six-kilobyte dependency bundles into the single deployable
 * file where the SDK would add megabytes for operations that are never called.
 */
export class R2Store implements ObjectStore {
  private readonly client: AwsClient;
  private readonly base: string;

  constructor(credentials: R2Credentials) {
    this.client = new AwsClient({
      accessKeyId: credentials.accessKeyId,
      secretAccessKey: credentials.secretAccessKey,
      service: "s3",
      region: "auto",
      retries: credentials.retries ?? 3,
    });
    this.base =
      `https://${credentials.accountId}.r2.cloudflarestorage.com/${credentials.bucket}`;
  }

  // encodeURI rather than encodeURIComponent: object keys contain slashes
  // (`internal/breaker/celestrak.json`) and those are path separators here.
  private url(key: string): string {
    return `${this.base}/${encodeURI(key)}`;
  }

  /**
   * A signed request, saying which object it was for if it never gets sent.
   *
   * Node reports an unreachable R2 exactly as it reports an unreachable
   * CelesTrak - `fetch failed`, with the reason nested underneath - and the two
   * are fixed in entirely different places. Naming the operation and the key
   * here is what tells them apart in the journal.
   */
  private async send(key: string, init: RequestInit): Promise<Response> {
    try {
      return await this.client.fetch(this.url(key), init);
    } catch (error) {
      throw new Error(
        `R2 ${init.method ?? "GET"} ${key} could not be sent: ${explainFailure(error)}`,
        { cause: error },
      );
    }
  }

  async get(key: string): Promise<string | null> {
    const response = await this.send(key, { method: "GET" });
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new Error(`R2 GET ${key} returned ${response.status}`);
    }
    return response.text();
  }

  async put(key: string, body: string, options: PutOptions = {}): Promise<void> {
    const headers: Record<string, string> = {};
    if (options.contentType !== undefined) {
      headers["Content-Type"] = options.contentType;
    }
    if (options.cacheControl !== undefined) {
      headers["Cache-Control"] = options.cacheControl;
    }

    const response = await this.send(key, { method: "PUT", body, headers });
    if (!response.ok) {
      throw new Error(`R2 PUT ${key} returned ${response.status}`);
    }
  }
}
