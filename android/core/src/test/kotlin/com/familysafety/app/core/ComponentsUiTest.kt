package com.familysafety.app.core

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.familysafety.app.core.designsystem.BarChart
import com.familysafety.app.core.designsystem.BarEntry
import com.familysafety.app.core.designsystem.ConfirmDialog
import com.familysafety.app.core.designsystem.EmptyState
import com.familysafety.app.core.designsystem.ErrorState
import com.familysafety.app.core.designsystem.FamilySafetyTheme
import com.familysafety.app.core.designsystem.PasswordField
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ComponentsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `error state retry works`() {
        var retried = false
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { ErrorState("Unable to reach the server.", onRetry = { retried = true }) } }
        compose.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }

    @Test
    fun `empty state action`() {
        var clicked = false
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { EmptyState("No devices", "Pair one", actionLabel = "Pair a device") { clicked = true } } }
        compose.onNodeWithText("Pair a device").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `confirm dialog requires explicit confirmation`() {
        var confirmed = false
        compose.setContent {
            FamilySafetyTheme(dynamicColor = false) { ConfirmDialog("Remove device?", "This signs the device out.", "Remove", { confirmed = true }, {}, destructive = true) }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `password visibility toggle is accessible`() {
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { PasswordField("secret", {}, "Password") } }
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "night")
    fun `chart exposes data to screen readers in dark theme`() {
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { BarChart(listOf(BarEntry("Mon", 60_000, "1 min"), BarEntry("Tue", 0, "0 min"))) } }
        compose.onNodeWithContentDescription("Usage chart. Mon: 1 min; Tue: 0 min").assertIsDisplayed()
    }
}
