package dev.herdertower.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.herdertower.phone.api.HistoryEntry

private val now = System.currentTimeMillis()

private val sampleHome = HomeUi(
    greeting = "Good evening",
    towerName = "Studio Mac",
    needsLine = "2 sessions need you",
    statusLine = "Ready · Focus: B2C support",
    connected = true,
    alerts = 3,
    suggestions = listOf(
        Suggestion.Answer("t3", "api", "Wants to run the migration"),
        Suggestion.Ask("Status of all sessions", "What's the status of all sessions?"),
        Suggestion.Ask("Catch me up", "Catch me up: what happened while I was away?"),
        Suggestion.Focus("Infra", "Hold other spaces' news"),
    ),
    lastAnswer = HistoryEntry(at = now - 120_000, kind = "answer", text = "api is **blocked**; web is building the landing page."),
    focus = "B2C support",
    spaces = listOf("B2C support", "Infra"),
    voiceOn = true,
)

private val sampleChat = listOf(
    HistoryEntry(at = now - 600_000, kind = "request", text = "[voice] what's everyone doing?"),
    HistoryEntry(at = now - 590_000, kind = "answer", text = "Three sessions:\n- **api**: blocked, wants `npm run migrate`\n- **web**: building the landing page\n- deploy: idle"),
    HistoryEntry(at = now - 300_000, kind = "job", session = "t3", name = "api", status = "blocked", text = "wants to run the migration", account = "eyz"),
    HistoryEntry(at = now - 60_000, kind = "request", text = "Approve the migration, staging only"),
)

@Composable
private fun HomePreview(mode: String) = TowerTheme(mode) {
    Backdrop("idle", needsYou = true) {
        HomeContent(sampleHome, "", false, onDraft = {}, onSend = {}, onMic = {}, onSuggestion = {}, onFocus = {}, onVoiceToggle = {}, onAlerts = {}, onMenu = {}, onChat = {})
    }
}

@Composable
private fun ChatPreview(mode: String) = TowerTheme(mode) {
    Backdrop("thinking", needsYou = false) {
        ChatContent(
            sampleChat, working = true, connected = true, accounts = AccountLookup(mapOf("t3" to "eyz"), "main"),
            draft = "", sending = false, voiceOn = true, onDraft = {}, onSend = {}, onMic = {}, onClose = {}, onLoadOlder = {}, onOpen = {}, onVoiceToggle = {},
        )
    }
}

@Preview(name = "Home, dark", widthDp = 390, heightDp = 844)
@Composable
private fun HomeDark() = HomePreview("dark")

@Preview(name = "Home, light", widthDp = 390, heightDp = 844)
@Composable
private fun HomeLight() = HomePreview("light")

@Preview(name = "Chat, dark", widthDp = 390, heightDp = 844)
@Composable
private fun ChatDark() = ChatPreview("dark")

@Preview(name = "Chat, light", widthDp = 390, heightDp = 844)
@Composable
private fun ChatLight() = ChatPreview("light")
