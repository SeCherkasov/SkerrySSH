package app.skerry.server

/**
 * Resolves the per-IP rate-limit key for a request, correct behind a reverse proxy.
 *
 * All buckets key on the client's IP. Without this, `X-Forwarded-For` is invisible and every request
 * behind a proxy shares the proxy's IP → one shared bucket (a single abuser exhausts everyone's SRP
 * budget, and per-client brute-force isolation is lost). But blindly trusting the header lets any
 * client spoof its IP to dodge limits. So the header is honoured only when the *direct* peer is a
 * configured trusted proxy:
 *
 *  - [trustedProxies] empty (no proxy) ⇒ key on [directPeer] (the socket peer), as before.
 *  - direct peer not in [trustedProxies] ⇒ key on [directPeer]; the header is attacker-controlled noise.
 *  - direct peer is trusted ⇒ walk [forwardedFor] right-to-left (rightmost = added by the nearest
 *    proxy) and take the first entry that is not itself a trusted proxy — the real client through a
 *    chain of trusted hops. If the chain yields nothing usable, fall back to [directPeer].
 *
 * Exact-IP match (not CIDR): the allowlist is a short list of known proxy addresses.
 */
fun rateLimitClientKey(
    directPeer: String,
    forwardedFor: String?,
    trustedProxies: Set<String>,
): String {
    if (trustedProxies.isEmpty() || directPeer !in trustedProxies) return directPeer
    val chain = forwardedFor
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return chain.lastOrNull { it !in trustedProxies } ?: directPeer
}
