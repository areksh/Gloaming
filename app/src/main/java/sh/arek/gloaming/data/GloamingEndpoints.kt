package sh.arek.gloaming.data

/**
 * The two constants that genuinely have to be compiled into the APK.
 *
 * Everything else the app talks to comes from the manifest, so it can move
 * without stranding installs that never update. These two cannot: the manifest
 * URL is where the app starts, and the public key is what makes the manifest
 * trustworthy. Changing either means shipping a new release.
 */
object GloamingEndpoints {

    /** The only URL baked into the app. */
    const val MANIFEST_URL: String = "https://sky.arek.sh/v1/index.json"

    /**
     * Ed25519 public key for the manifest signature, raw 32 bytes.
     *
     * The private half exists only as a Cloudflare Worker secret. If it is ever
     * rotated, this constant and a new release must go out together, and old
     * installs will keep their last known-good manifest until they update.
     */
    val MANIFEST_PUBLIC_KEY: ByteArray =
        "225e9e2dbb74f7daedc7ff9d9f5bda8714068a2ad7ecf27a312838fc6bc6d93d"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
