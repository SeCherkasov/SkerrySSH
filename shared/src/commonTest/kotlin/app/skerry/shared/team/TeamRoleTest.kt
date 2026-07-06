package app.skerry.shared.team

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamRoleTest {

    @Test
    fun `fromWire maps known roles and degrades unknown to viewer`() {
        assertEquals(TeamRole.OWNER, TeamRole.fromWire("owner"))
        assertEquals(TeamRole.ADMIN, TeamRole.fromWire("admin"))
        assertEquals(TeamRole.EDITOR, TeamRole.fromWire("editor"))
        assertEquals(TeamRole.VIEWER, TeamRole.fromWire("viewer"))
        // legacy role from before granular roles: member could write records -> editor
        assertEquals(TeamRole.EDITOR, TeamRole.fromWire("member"))
        // unknown role: least privilege
        assertEquals(TeamRole.VIEWER, TeamRole.fromWire("superadmin"))
        assertEquals(TeamRole.VIEWER, TeamRole.fromWire(""))
    }

    @Test
    fun `wire round-trips every role`() {
        for (role in TeamRole.entries) assertEquals(role, TeamRole.fromWire(role.wire))
    }

    @Test
    fun `capabilities follow the hierarchy`() {
        assertTrue(TeamRole.OWNER.canManageMembers && TeamRole.OWNER.canWrite && TeamRole.OWNER.canViewAudit)
        assertTrue(TeamRole.ADMIN.canManageMembers && TeamRole.ADMIN.canWrite && TeamRole.ADMIN.canViewAudit)
        assertFalse(TeamRole.EDITOR.canManageMembers)
        assertTrue(TeamRole.EDITOR.canWrite)
        assertFalse(TeamRole.EDITOR.canViewAudit)
        assertFalse(TeamRole.VIEWER.canWrite || TeamRole.VIEWER.canManageMembers || TeamRole.VIEWER.canViewAudit)
    }

    @Test
    fun `assignable roles enforce anti-escalation`() {
        assertEquals(listOf(TeamRole.ADMIN, TeamRole.EDITOR, TeamRole.VIEWER), TeamRole.OWNER.assignableRoles())
        assertEquals(listOf(TeamRole.EDITOR, TeamRole.VIEWER), TeamRole.ADMIN.assignableRoles())
        assertTrue(TeamRole.EDITOR.assignableRoles().isEmpty())
        assertTrue(TeamRole.VIEWER.assignableRoles().isEmpty())
    }
}
