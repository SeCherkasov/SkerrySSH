package app.skerry.ui.teams

import app.skerry.shared.team.RecordingMode
import app.skerry.shared.team.RecordingPolicy
import app.skerry.shared.team.TeamScopeRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingPolicyFormTest {
    @Test fun unknownPolicyCannotBeSavedAsOff() {
        assertFalse(RecordingPolicyForm().canSave)
    }
    @Test fun confirmedAbsentPolicyCanBeCreatedButCannotBeSavedUnchanged() {
        val form = RecordingPolicyForm().loaded(null)
        assertFalse(form.canSave)
        assertTrue(form.copy(mode = RecordingMode.REQUIRED).canSave)
    }
    @Test fun unchangedAndSavingPoliciesCannotBeSaved() {
        val form = RecordingPolicyForm().loaded(RecordingPolicy(TeamScopeRef("team"), RecordingMode.REQUIRED, 1, 0, 30))
        assertFalse(form.canSave)
        assertTrue(form.copy(mode = RecordingMode.OPTIONAL).canSave)
        assertFalse(form.copy(mode = RecordingMode.OPTIONAL, busy = true).canSave)
    }
    @Test fun loadFailureInvalidatesPreviouslyLoadedPolicy() {
        val form = RecordingPolicyForm().loaded(RecordingPolicy(TeamScopeRef("team"), RecordingMode.REQUIRED, 1, 0, 30))
        assertFalse(form.copy(mode = RecordingMode.OFF).loadFailed().canSave)
    }
}
