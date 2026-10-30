package dev.anydm.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** The presenters of the network, OS and tool sections of the settings screen (#609), made together and read when their section opens. */
class SettingsPanels(
    source: EngineSource,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    val window: WindowModel = WindowModel(MemoryAppPrefs(), trayAvailable = false),
    linkHandlers: LinkHandlers = NoLinkHandlers(),
) {
    val proxies = ProxyModel(source, scope)
    val rules = RulesModel(source, scope)
    val networks = NetworksModel(source, scope)
    val os = OsModel(source, scope)
    val links = LinkHandlerModel(linkHandlers, scope, io)
    val tools = ToolsModel(source, scope, io = io)
    val diagnostics = DiagnosticsModel(source, scope)

    /** Reads the engine again for [section]; sections that are not one of these have nothing to read here. */
    fun load(section: Section) {
        when (section) {
            Section.PROXIES -> {
                proxies.load()
            }

            Section.RULES -> {
                rules.load()
            }

            Section.SYSTEM -> {
                os.load()
                links.load()
            }

            Section.TOOLS -> {
                tools.load()
            }

            Section.DIAGNOSTICS -> {
                diagnostics.load()
            }

            else -> {
                Unit
            }
        }
    }
}
