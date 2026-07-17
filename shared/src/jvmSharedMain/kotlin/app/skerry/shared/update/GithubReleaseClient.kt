package app.skerry.shared.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Fetches the latest published release from the GitHub Releases API (unauthenticated: the
 * 60 req/h anonymous limit is plenty for a once-a-day check). Any non-success status or
 * unparseable body yields null; transport exceptions propagate to [UpdateChecker], which
 * swallows them.
 */
class GithubReleaseClient(
    private val http: HttpClient,
    private val endpoint: String = LATEST_RELEASE_URL,
) {

    suspend fun fetchLatest(): ReleaseInfo? {
        val response = http.get(endpoint) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) return null
        return parseLatestRelease(response.bodyAsText())
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/onepve/SkerrySSH/releases/latest"

        /** Bare CIO client — the check is a single small GET, no negotiation plugins needed. */
        fun defaultHttpClient(): HttpClient = HttpClient(CIO)

        /**
         * One-shot fetch with a throwaway client: the check runs at most once a day, so pooling
         * a connection between checks would only keep an idle socket around.
         */
        suspend fun fetchOnce(): ReleaseInfo? = defaultHttpClient().use { GithubReleaseClient(it).fetchLatest() }
    }
}
