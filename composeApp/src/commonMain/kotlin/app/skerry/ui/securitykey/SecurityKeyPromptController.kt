package app.skerry.ui.securitykey

import app.skerry.shared.securitykey.SecurityKeyException
import app.skerry.shared.securitykey.SecurityKeyFailure
import app.skerry.shared.securitykey.SecurityKeyOperation
import app.skerry.shared.securitykey.SecurityKeyPrompter
import app.skerry.shared.securitykey.SecurityKeyRequestContext
import app.skerry.ui.design.PromptQueue

/** Local authenticator questions never enter the remote keyboard-interactive/PAM path. */
data class SecurityKeyQuestion(
    val id: Long,
    val context: SecurityKeyRequestContext,
    val pin: Boolean,
    val retries: Int?,
)

class SecurityKeyPromptController : SecurityKeyPrompter {
    private val queue = PromptQueue<SecurityKeyQuestion, CharArray?>(120_000, { it.id })
    val pending = queue.pending
    private val generation = SecurityKeyRequestContext(application = "ssh:", operation = SecurityKeyOperation.Generate)

    override fun forRequest(context: SecurityKeyRequestContext): SecurityKeyPrompter = object : SecurityKeyPrompter {
        override suspend fun requestPin(retries: Int?): CharArray? = ask(context, true, retries)
        override suspend fun onTouch() {
            val answer = ask(context, false, null) ?: throw SecurityKeyException(SecurityKeyFailure.Cancelled)
            answer.fill('\u0000')
        }
    }
    override suspend fun requestPin(retries: Int?): CharArray? = ask(generation, true, retries)
    override suspend fun onTouch() = forRequest(generation).onTouch()
    private suspend fun ask(context: SecurityKeyRequestContext, pin: Boolean, retries: Int?): CharArray? =
        queue.ask({ SecurityKeyQuestion(it, context, pin, retries) }, null)

    fun answer(id: Long, pin: CharArray?) {
        if (!queue.offer(id, pin)) pin?.fill('\u0000')
    }
    fun cancelPending() = queue.cancelPending(null)
}
