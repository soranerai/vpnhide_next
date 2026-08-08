package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.db.PolicyListMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppFiltersTest {
    private val unselected = AppEntry("com.example.one", "One", null, isSystem = false)
    private val selected = AppEntry("com.example.two", "Two", null, isSystem = false, lsposed = true)
    private val system = AppEntry("com.android.system", "System", null, isSystem = true, uid = 10004)

    @Test
    fun allowlistOnlyExceptionsUsesStagedSelection() {
        val result =
            filterAndSortApps(
                apps = listOf(unselected, selected),
                listMode = PolicyListMode.ALLOWLIST,
                searchQuery = "",
                showSystem = false,
                showRussianOnly = false,
                showOnlySelected = true,
                showOnlyWorkProfile = false,
                sortOrder = AppSortOrder.NAME_ASC,
            )

        assertEquals(listOf(selected), result)
    }

    @Test
    fun allowlistSystemFilterCanBeEnabled() {
        val result =
            filterAndSortApps(
                apps = listOf(unselected, selected, system),
                listMode = PolicyListMode.ALLOWLIST,
                searchQuery = "",
                showSystem = true,
                showRussianOnly = true,
                showOnlySelected = false,
                showOnlyWorkProfile = false,
                sortOrder = AppSortOrder.NAME_ASC,
            )

        assertEquals(listOf(unselected, system, selected), result)
    }

    @Test
    fun allowlistHidesImplicitSystemExceptionsButKeepsOverridesVisible() {
        val implicit = system.copy(lsposed = true)
        val explicit =
            system.copy(
                packageName = "com.android.explicit",
                label = "Explicit",
                systemPolicyExplicit = true,
            )
        val result =
            filterAndSortApps(
                apps = listOf(unselected, implicit, explicit),
                listMode = PolicyListMode.ALLOWLIST,
                searchQuery = "",
                showSystem = false,
                showRussianOnly = false,
                showOnlySelected = false,
                showOnlyWorkProfile = false,
                sortOrder = AppSortOrder.NAME_ASC,
            )

        assertEquals(listOf(explicit, unselected), result)
    }

    @Test
    fun systemPolicyOverrideIsSparseAndModeAware() {
        val allowlistDefault = system.copy(kmod = true, lsposed = true, portHiding = true)
        assertEquals(false, allowlistDefault.withNormalizedSystemPolicy(PolicyListMode.ALLOWLIST, true).systemPolicyExplicit)
        assertEquals(
            true,
            allowlistDefault
                .copy(lsposed = false)
                .withNormalizedSystemPolicy(PolicyListMode.ALLOWLIST, true)
                .systemPolicyExplicit,
        )
        assertEquals(
            true,
            system.copy(kmod = true).withNormalizedSystemPolicy(PolicyListMode.BLACKLIST, true).systemPolicyExplicit,
        )
    }

    @Test
    fun riskySystemTransitionFollowsModeDirection() {
        val off = system
        val on = system.copy(lsposed = true)
        assertEquals(true, isRiskySystemTransition(off, on, PolicyListMode.BLACKLIST))
        assertEquals(false, isRiskySystemTransition(on, off, PolicyListMode.BLACKLIST))
        assertEquals(true, isRiskySystemTransition(on, off, PolicyListMode.ALLOWLIST))
        assertEquals(false, isRiskySystemTransition(off, on, PolicyListMode.ALLOWLIST))
    }

    @Test
    fun implicitSystemDefaultsStaySparseButExplicitDeselectionPersists() {
        val implicit = system.copy(kmod = true, lsposed = true, portHiding = true)
        assertEquals(false, implicit.shouldPersistPolicy(null, null))
        assertEquals(true, implicit.copy(portRules = listOf(PortRule(startPort = 443))).shouldPersistPolicy(null, null))
        assertEquals(
            true,
            system.copy(systemPolicyExplicit = true).shouldPersistPolicy(null, null),
        )
    }

    @Test
    fun unselectedFirstSortsBySelectionThenName() {
        val result =
            filterAndSortApps(
                apps = listOf(selected, unselected, system),
                listMode = PolicyListMode.BLACKLIST,
                searchQuery = "",
                showSystem = true,
                showRussianOnly = false,
                showOnlySelected = false,
                showOnlyWorkProfile = false,
                sortOrder = AppSortOrder.UNSELECTED_FIRST,
            )

        assertEquals(listOf(unselected, system, selected), result)
    }

    @Test
    fun manualExceptionCountExcludesImplicitSystemSelections() {
        val automaticSystem = system.copy(lsposed = true, portHiding = true)
        val manualSystem = automaticSystem.copy(systemPolicyExplicit = true, portHiding = false)

        assertEquals(
            2,
            listOf(selected, automaticSystem, manualSystem).manualSelectionCount(PolicyListMode.ALLOWLIST),
        )
        assertEquals(true, automaticSystem.isAutomaticallySelectedSystem(PolicyListMode.ALLOWLIST))
        assertEquals(false, manualSystem.isAutomaticallySelectedSystem(PolicyListMode.ALLOWLIST))
    }

    @Test
    fun searchMatchesNamePackageAndUid() {
        fun search(query: String): List<AppEntry> =
            filterAndSortApps(
                apps = listOf(unselected, selected, system),
                listMode = PolicyListMode.BLACKLIST,
                searchQuery = query,
                showSystem = true,
                showRussianOnly = false,
                showOnlySelected = false,
                showOnlyWorkProfile = false,
                sortOrder = AppSortOrder.NAME_ASC,
            )

        assertEquals(listOf(unselected), search("one"))
        assertEquals(listOf(selected), search("EXAMPLE.TWO"))
        assertEquals(listOf(system), search("10004"))
    }
}
