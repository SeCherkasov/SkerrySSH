package app.skerry.ui.teams

import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.RecordingPolicy

internal data class RecordingPolicyForm(
    val policy: RecordingPolicy? = null,
    val mode: RecordingMode = RecordingMode.OFF,
    val retentionDays: Int = 30,
    val busy: Boolean = false,
    val loaded: Boolean = false,
) {
    val canSave: Boolean get() = !busy && loaded &&
        (mode != (policy?.mode ?: RecordingMode.OFF) || retentionDays != (policy?.retentionDays ?: 30))

    fun loaded(value: RecordingPolicy?) = copy(policy = value, mode = value?.mode ?: RecordingMode.OFF,
        retentionDays = value?.retentionDays ?: 30, loaded = true)
    fun loadFailed() = copy(policy = null, loaded = false)
}
