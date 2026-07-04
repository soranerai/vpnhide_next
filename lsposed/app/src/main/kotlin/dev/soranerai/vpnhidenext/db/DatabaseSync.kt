package dev.soranerai.vpnhidenext.db

import android.content.Context
import dev.soranerai.vpnhidenext.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object DatabaseSync {
    suspend fun sync(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val appDao = db.appDao()
            val portRuleDao = db.portRuleDao()
            val massPortRuleDao = db.massPortRuleDao()
            val ifacePrefixDao = db.ifacePrefixDao()

            val apps = appDao.getAllAppProtectionSync()
            val massRules = massPortRuleDao.getMassRulesSync()
            val ifacePrefixes = ifacePrefixDao.getAllPrefixesSync()

            val globalConfigDao = db.globalConfigDao()
            val config = globalConfigDao.getConfig() ?: DbGlobalConfig()

            val selfUid = context.applicationInfo.uid

            val parts = mutableListOf<String>()

            // Sync active hooks masks from database
            parts += "$KMOD_CTL active_hooks ${config.kernelHookMask}"
            parts += "$KMOD_CTL java_hooks ${config.javaHookMask}"

            // 2. Build and apply VPN targets directly to the kernel module
            val kmodUids = (apps.filter { it.kmod }.map { it.uid } + selfUid).distinct().sorted()
            parts += buildKmodApplyCommand(kmodUids, targetType = "targets")

            val lsposedUids = (apps.filter { it.lsposed }.map { it.uid } + selfUid).distinct().sorted()
            parts += buildKmodApplyCommand(lsposedUids, targetType = "lsposed_targets")

            // 3. Build and apply Interface prefixes directly to the kernel module
            val ifaceApplyCmd =
                if (ifacePrefixes.isEmpty()) {
                    "$KMOD_CTL iface_prefixes"
                } else {
                    "$KMOD_CTL iface_prefixes ${ifacePrefixes.joinToString(" ")}"
                }
            parts += ifaceApplyCmd

            // 4. Build and apply Port Hiding rules directly to the kernel module
            val portApps = apps.filter { it.portHiding }
            val ruleMap = mutableMapOf<Int, List<PortRule>>()
            portApps.forEach { app ->
                val appRules =
                    portRuleDao.getRulesForAppSync(app.packageName, app.userId).map {
                        PortRule(
                            id = it.id.toString(),
                            startPort = it.startPort,
                            endPort = it.endPort,
                            protocol = it.protocol,
                            label = it.label,
                            enabled = it.enabled,
                        )
                    }
                val activeMassRules =
                    massRules.filter { it.enabled }.map {
                        PortRule(
                            id = it.id.toString(),
                            startPort = it.startPort,
                            endPort = it.endPort,
                            protocol = it.protocol,
                            label = it.label,
                            enabled = it.enabled,
                        )
                    }
                ruleMap[app.uid] = appRules + activeMassRules
            }
            parts += buildKmodPortRulesApplyCommand(ruleMap)

            // Execute all consolidated commands
            if (parts.isNotEmpty()) {
                val (exitCode, _) = suExec(parts.joinToString(" ; "))
                exitCode == 0
            } else {
                true
            }
        }
}
