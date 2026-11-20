package com.familysafety.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.familysafety.app.core.designsystem.FamilySafetyTheme
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.repository.AuthRepository
import com.familysafety.app.ui.RoleContent
import com.familysafety.app.ui.auth.AuthContent
import com.familysafety.app.ui.auth.AuthForm
import com.familysafety.app.ui.auth.AuthViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val repo = mockk<AuthRepository>(relaxed = true)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `client-side validation blocks bad input without calling the server`() {
        val vm = AuthViewModel(repo, null)
        vm.update { it.copy(email = "not-an-email", password = "x") }
        vm.submit()
        assertEquals("Enter a valid email address.", vm.form.value.error)
        vm.toggleMode()
        vm.update { it.copy(email = "a@b.co", password = "short", displayName = "A") }
        vm.submit()
        assertEquals("Password must be 12–128 characters.", vm.form.value.error)
        coVerify(exactly = 0) { repo.register(any(), any(), any(), any()); repo.login(any(), any()) }
    }

    @Test
    fun `registration sends the selected role`() {
        coEvery { repo.register(any(), any(), any(), any()) } returns AppResult.Success(Unit)
        val vm = AuthViewModel(repo, null)
        vm.toggleMode()
        vm.update { it.copy(email = "kid@example.test", password = "long enough pass", displayName = "Kid", role = Role.CHILD) }
        vm.submit()
        coVerify { repo.register("kid@example.test", "long enough pass", "Kid", Role.CHILD) }
        assertNull(vm.form.value.error)
        assertEquals("", vm.form.value.password) // password is not kept in memory after success
    }

    @Test
    fun `server errors are shown to the user`() {
        coEvery { repo.login(any(), any()) } returns AppResult.Failure(AppError.Api(401, "INVALID_CREDENTIALS", "Email or password is incorrect."))
        val vm = AuthViewModel(repo, null)
        vm.update { it.copy(email = "p@example.test", password = "whatever") }
        vm.submit()
        assertEquals("Email or password is incorrect.", vm.form.value.error)
        assertEquals(false, vm.form.value.busy)
    }
}

/** Uses a plain Application: the production Hilt application needs AndroidKeyStore, which the JVM lacks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class AuthAndRoutingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `registration form offers both roles and explains the child role`() {
        compose.setContent {
            var form by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AuthForm(registering = true)) }
            FamilySafetyTheme(dynamicColor = false) {
                AuthContent(form, onChange = { t -> form = t(form) }, onToggleMode = {}, onSubmit = {})
            }
        }
        compose.onNodeWithTag("role_PARENT").assertIsDisplayed()
        compose.onNodeWithText("Parents pair their children's devices and receive safety alerts.").assertIsDisplayed()
        compose.onNodeWithTag("role_CHILD").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithText("Children connect their own phone using a code from a parent. You'll always see what is shared.").assertExists()
    }

    @Test
    fun `errors are rendered`() {
        compose.setContent {
            FamilySafetyTheme(dynamicColor = false) { AuthContent(AuthForm(error = "Email or password is incorrect."), {}, {}, {}) }
        }
        compose.onNodeWithTag("auth_error").assertIsDisplayed()
        compose.onNodeWithTag("submit").assertIsDisplayed()
    }

    @Test
    fun `loading auth state shows a progress screen`() {
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { RoleContent(AuthState.Loading, null) } }
        compose.onNodeWithText("Starting…").assertIsDisplayed()
    }
}

/** New auth UX: server set-up prompt, password requirement indicator, network errors offer server settings. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class AuthServerUxTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `missing server shows a setup prompt that opens server settings`() {
        var opened = false
        compose.setContent {
            FamilySafetyTheme(dynamicColor = false) { AuthContent(AuthForm(), {}, {}, {}, serverHost = "api.familysafety.example", serverConfigured = false, onOpenServer = { opened = true }) }
        }
        compose.onNodeWithTag("setup_server_card").assertIsDisplayed()
        compose.onNodeWithText("Set up server").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `configured server host is shown and no setup prompt`() {
        compose.setContent {
            FamilySafetyTheme(dynamicColor = false) { AuthContent(AuthForm(), {}, {}, {}, serverHost = "fs.example.org", serverConfigured = true) }
        }
        compose.onNodeWithText("fs.example.org").assertIsDisplayed()
        compose.onNodeWithTag("setup_server_card").assertDoesNotExist()
    }

    @Test
    fun `password requirement reacts to input`() {
        compose.setContent {
            FamilySafetyTheme(dynamicColor = false) { AuthContent(AuthForm(registering = true, password = "twelve chars!"), {}, {}, {}) }
        }
        compose.onNodeWithText("At least 12 characters ✓").assertExists()
    }

    @Test
    fun `network errors offer the server settings`() {
        compose.setContent {
            FamilySafetyTheme(dynamicColor = false) {
                AuthContent(AuthForm(error = "Can't find the server.", errorIsNetwork = true), {}, {}, {})
            }
        }
        compose.onNodeWithText("Server settings").assertExists()
    }

    @Test
    fun `server settings sheet content shows validation and results`() {
        compose.setContent {
            FamilySafetyTheme(dynamicColor = false) {
                com.familysafety.app.ui.auth.ServerSettingsContent(
                    com.familysafety.app.ui.auth.ServerSettingsState(input = "https://x.org", check = com.familysafety.app.ui.auth.ConnectionCheck.Failed("The server refused the connection.")),
                    {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithTag("server_error").assertIsDisplayed()
    }
}
