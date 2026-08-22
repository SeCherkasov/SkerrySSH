package app.skerry.shared.trust

/**
 * Trust decider that answers [answer] and keeps what it was asked — the stand-in for the dialog in
 * the verifier tests, on both sides of the store (SSH keys in commonTest, RDP certificates in
 * desktopTest).
 */
internal class RecordingHostTrust(private val answer: Boolean) : HostTrustDecider {
    val requests = mutableListOf<HostTrustRequest>()

    override fun decide(request: HostTrustRequest): Boolean {
        requests += request
        return answer
    }
}
