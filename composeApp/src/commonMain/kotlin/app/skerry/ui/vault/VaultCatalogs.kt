package app.skerry.ui.vault

import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.ui.ai.AiAssistantController
import app.skerry.ui.host.HostManagerController
import app.skerry.ui.identity.CredentialManagerController
import app.skerry.ui.known.KnownHostsController
import app.skerry.ui.runbook.RunbookManager
import app.skerry.ui.snippet.SnippetManager
import app.skerry.ui.tunnel.TunnelManager
import app.skerry.ui.update.UpdateNoticeController

/** Catalog dependencies shared by the two platform graphs, including singleton order records. */
class VaultCatalogs(
    hosts: HostManagerController,
    snippets: SnippetManager,
    runbooks: RunbookManager,
    tunnels: TunnelManager,
    knownHosts: KnownHostsController,
    credentials: CredentialManagerController,
) {
    private val catalogs = buildList {
        add(VaultCatalogReload(setOf(RecordType.HOST, RecordType.GROUP), hosts::prepareReload))
        add(VaultCatalogReload(setOf(RecordType.SNIPPET, RecordType.LIBRARY_ORDER), snippets::prepareReload))
        add(VaultCatalogReload(setOf(RecordType.RUNBOOK, RecordType.LIBRARY_ORDER), runbooks::prepareReload))
        add(VaultCatalogReload(setOf(RecordType.TUNNEL), tunnels::prepareReload))
        add(VaultCatalogReload(setOf(RecordType.KNOWN_HOST), knownHosts::prepareRefresh))
        add(VaultCatalogReload(setOf(RecordType.CREDENTIAL), credentials::prepareReload))
    }

    fun reloader(
        vault: Vault,
        ai: AiAssistantController? = null,
        updates: UpdateNoticeController? = null,
    ): VaultCatalogReloader = VaultCatalogReloader(vault, catalogs + vaultSettingsCatalogs(ai, updates))
}

/** Android constructs these settings controllers in composition rather than in its platform graph. */
fun vaultSettingsReloader(
    vault: Vault,
    ai: AiAssistantController?,
    updates: UpdateNoticeController?,
): VaultCatalogReloader = VaultCatalogReloader(vault, vaultSettingsCatalogs(ai, updates))

fun vaultSettingsCatalogs(ai: AiAssistantController?, updates: UpdateNoticeController?) = buildList {
    ai?.let { add(VaultCatalogReload(setOf(RecordType.SETTINGS), it::prepareRefresh)) }
    updates?.let { add(VaultCatalogReload(setOf(RecordType.SETTINGS), it::prepareRefresh)) }
}
