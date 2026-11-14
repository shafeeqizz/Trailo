package com.trailo.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailo.app.domain.convoy.*
import com.trailo.app.ui.convoy.*
import com.trailo.app.ui.theme.TrailoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConvoyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun show(state: ConvoyUiState, onRetry: () -> Unit = {}, onEnd: () -> Unit = {}) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                TrailoTheme { ConvoyScreen(state, {}, {}, {}, {}, {}, {}, onEnd, onRetry, {}, {}, {}) }
            }
        }
    }
    @Test fun emptyLobbyAllowsCreateAndJoin() {
        show(ConvoyUiState(session = ConvoySession.Empty))
        compose.onNodeWithText("Convoy name").assertExists()
        compose.onNodeWithText("Join convoy").assertExists()
        compose.onNodeWithText("You have no active convoy.").assertExists()
    }
    @Test fun uncertainRequestDisablesMutationsUntilSessionIsChecked() {
        show(ConvoyUiState(name = "Trip", session = ConvoySession.Empty,
            reconciling = true, mutation = MutationStatus.UNKNOWN))
        compose.onNodeWithText("Create convoy").assertIsNotEnabled()
        compose.onNodeWithText("Join convoy").assertIsNotEnabled()
        compose.onNodeWithText("The request timed out or its response was lost. Its outcome is unknown. Checking your session before allowing another action.").assertExists()
    }
    @Test fun creatorMustConfirmBeforeEnding() {
        var ended = false
        val active = ConvoySession.Active(Convoy("id", "Trip", "owner", "owner", listOf("owner", "member"), Long.MAX_VALUE), "owner")
        show(ConvoyUiState(session = active)) { ended = true }
        compose.onNodeWithText("Generate new invitation").performScrollTo().assertExists()
        compose.onNodeWithText("End convoy").performScrollTo().performClick()
        compose.onNodeWithText("End this convoy for everyone and revoke all invitations?").assertExists()
        assertFalse(ended)
        compose.onAllNodesWithText("End convoy").onLast().performClick()
        compose.runOnIdle { assertTrue(ended) }
    }
    @Test fun unresolvedRequestOffersReadOnlyRecoveryAndNavigationWithMembershipVisible() {
        var checked=false
        val active=ConvoySession.Active(Convoy("id","Trip","owner","owner",listOf("owner"),Long.MAX_VALUE),"owner")
        show(ConvoyUiState(session=active,mutation=MutationStatus.UNKNOWN,
            recovery=OperationRecovery(ConvoyOperation.INVITE,RecoveryStatus.UNRESOLVED)), onRetry={checked=true})
        compose.onNodeWithText("Back to map").assertExists()
        compose.onNodeWithText("Trip").assertExists()
        compose.onNodeWithText("Generate new invitation").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Check pending request").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(checked)}
    }
    @Test fun regularMemberCanLeaveButCannotIssueOrEnd() {
        val active = ConvoySession.Active(Convoy("id", "Trip", "owner", "owner", listOf("owner", "member"), Long.MAX_VALUE), "member")
        show(ConvoyUiState(session = active))
        compose.onNodeWithText("Leave convoy").performScrollTo().assertExists()
        compose.onNodeWithText("Generate new invitation").assertDoesNotExist()
        compose.onNodeWithText("End convoy").assertDoesNotExist()
    }
}
