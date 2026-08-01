package dev.soranerai.vpnhidenext

import dev.soranerai.vpnhidenext.db.PolicyListMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppFiltersTest {
    private val unselected = AppEntry("com.example.one", "One", null, isSystem = false)
    private val selected = AppEntry("com.example.two", "Two", null, isSystem = false, lsposed = true)
    private val system = AppEntry("com.android.system", "System", null, isSystem = true)

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
    fun allowlistIgnoresBlacklistPresentationFilters() {
        val result =
            filterAndSortApps(
                apps = listOf(unselected, selected, system),
                listMode = PolicyListMode.ALLOWLIST,
                searchQuery = "",
                showSystem = false,
                showRussianOnly = true,
                showOnlySelected = false,
                showOnlyWorkProfile = false,
                sortOrder = AppSortOrder.NAME_ASC,
            )

        assertEquals(listOf(unselected, selected), result)
    }
}
