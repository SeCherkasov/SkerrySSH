package app.skerry.android

import android.os.PowerManager

/**
 * The lock itself, behind an interface so [WakeLockGate]'s transitions can be driven off a device.
 */
internal interface WakeLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Decides, and is the only thing that decides, whether a partial wake lock is held.
 *
 * Being a foreground service keeps the process, not the CPU: with the screen off the device still
 * suspends between wakeups, the SSH keepalive misses its interval, and the server drops a session
 * that was otherwise fine. So the lock is held exactly while sessions are open AND the user asked
 * for it — off by default, because the cost is battery.
 *
 * Its own class because the rule spans five transitions in [SessionKeepAliveService] (a session
 * added, the last one removed, a stray remove, an empty replay after a restart, and the switch
 * flipped under live sessions) and a lock left held with no session drains the battery invisibly
 * until the process dies.
 */
internal class WakeLockGate(private val newLock: () -> WakeLockHandle?) {

    private var lock: WakeLockHandle? = null

    fun sync(sessionCount: Int, enabled: Boolean) {
        if (sessionCount == 0 || !enabled) release() else acquire()
    }

    fun release() {
        val held = lock ?: return
        lock = null
        if (held.isHeld) held.release()
    }

    private fun acquire() {
        if (lock?.isHeld == true) return
        val held = lock ?: newLock() ?: return
        lock = held
        held.acquire()
    }
}

/**
 * Takes the lock without a timeout, on purpose. A timeout would expire under exactly the session it
 * is there for — an overnight `tail -f` gets no events to renew on — and the lock has three ends
 * that do not depend on one: the last session closing, the service being destroyed, and the process
 * dying (the kernel drops a dead process's locks). The foreground notification is up the whole time,
 * so the state is never invisible.
 */
internal class AndroidWakeLockHandle(private val lock: PowerManager.WakeLock) : WakeLockHandle {

    override val isHeld: Boolean get() = lock.isHeld

    @Suppress("WakelockTimeout")
    override fun acquire() {
        lock.acquire()
    }

    override fun release() {
        lock.release()
    }
}
