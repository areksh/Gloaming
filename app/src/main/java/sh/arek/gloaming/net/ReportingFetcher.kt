package sh.arek.gloaming.net

/**
 * A fetcher that records what it is doing into [Transfers].
 *
 * A decorator rather than a change to [OkHttpFetcher], for two reasons. The
 * transport has no business knowing that a screen exists; and everything the
 * app fetches already goes through one narrow interface, so wrapping it once in
 * `Graph` covers the manifest, the status document, the grid, the elements, the
 * comets and the forecast without a line in any repository.
 *
 * Nothing here changes an answer. The result is the delegate's, the ETag goes
 * through unaltered so conditional GET still carries the whole cost model, and
 * a failure is recorded and then rethrown - the repositories all fall back to
 * their caches on a throw, and swallowing it here would turn that into dead
 * code and quietly serve nothing instead of something old.
 */
class ReportingFetcher(
    private val delegate: HttpFetcher,
    private val now: () -> Long = System::currentTimeMillis,
) : HttpFetcher {

    override suspend fun get(url: String, etag: String?): HttpResult {
        Transfers.started(url, now())
        try {
            val result = delegate.get(url, etag)
            when (result) {
                is HttpResult.Ok -> Transfers.fresh(url, result.body.size, now())
                HttpResult.NotModified -> Transfers.unchanged(url, now())
            }
            return result
        } catch (cause: Throwable) {
            Transfers.failed(url, cause, now())
            throw cause
        }
    }
}
