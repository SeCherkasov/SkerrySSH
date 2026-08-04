package app.skerry.ui.runbook

import app.skerry.shared.runbook.ResolvedRunbookStep
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookTransferDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class RunbookStepTextTest {

    @Test
    fun `a command step reads as its command line`() {
        val step = RunbookStep.Command(id = "s1", title = "Reload nginx", command = "systemctl reload nginx")

        assertEquals("systemctl reload nginx", step.summaryLine())
    }

    @Test
    fun `an upload reads local first, remote second`() {
        val step = RunbookStep.Transfer(
            id = "s1",
            localPath = "release-0.2.1.tar.gz",
            remotePath = "/var/www/app/releases",
        )

        assertEquals("sftp: release-0.2.1.tar.gz → /var/www/app/releases", step.summaryLine())
    }

    @Test
    fun `a download turns the line around so the arrow still points where the file goes`() {
        val step = RunbookStep.Transfer(
            id = "s1",
            localPath = "/tmp/last.log",
            remotePath = "/var/log/app/last.log",
            direction = RunbookTransferDirection.DOWNLOAD,
        )

        assertEquals("sftp: /var/log/app/last.log → /tmp/last.log", step.summaryLine())
    }

    @Test
    fun `a resolved step reads the same way as the step it came from`() {
        val resolved = ResolvedRunbookStep.Transfer(
            localPath = "/tmp/0.2.1.tgz",
            remotePath = "/srv/releases/0.2.1.tgz",
            direction = RunbookTransferDirection.UPLOAD,
        )

        assertEquals("sftp: /tmp/0.2.1.tgz → /srv/releases/0.2.1.tgz", resolved.summaryLine())
        assertEquals("uptime", ResolvedRunbookStep.Command("uptime").summaryLine())
    }
}
