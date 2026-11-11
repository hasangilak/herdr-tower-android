package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdertower.phone.api.Assumed
import dev.herdertower.phone.api.LaunchPlan
import dev.herdertower.phone.api.Setup
import dev.herdertower.phone.data.Link
import dev.herdertower.phone.data.TowerSession
import kotlinx.coroutines.launch

/** What launching a space will do, in words, before it is done. */
object LaunchModel {
    /** The plan's lines for [setup]: what the tower will do without asking. */
    fun plan(setup: Setup?): List<String> = if (setup == null) listOf(
        "Name the space from the goal",
        "Pick a saved setup that fits",
        "Hand the goal out as the first request",
    ) else listOf(
        "Name the space from the goal",
        "Open ${setup.repos.size} session${if (setup.repos.size == 1) "" else "s"} on ${setup.account.ifBlank { "the default account" }}" +
            if (setup.repos.isNotEmpty()) ": " + setup.repos.joinToString(", ") { it.trimEnd('/').substringAfterLast('/') } else "",
        "Each in its own worktree, introduced to the others",
        "Hand the goal out as the first request",
    )

    /** What it assumed, as short labels. */
    fun assumed(a: Assumed): List<String> = listOfNotNull(a.name?.takeIf { it.isNotBlank() }, a.setup?.takeIf { it.isNotBlank() }, a.account?.takeIf { it.isNotBlank() })

    /** How long the goal rests before the tower is asked what launching it would do. */
    const val PLAN_AFTER_MS = 700L

    /** The words a tower without launching gets instead: its brain opens the space. */
    fun words(goal: String): String = "Open a new space for this goal, with the sessions it needs, and hand the goal out: $goal"
}

/**
 * A space from one sentence: say or type the goal, pick a saved setup, see
 * what the tower will do (a dry run: the space's name, a session per repo,
 * what it assumes), launch. Nothing is asked. The launch answers at once and
 * opens its sessions by itself: each shows as it opens, and the space's
 * view opens when it is done.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSpaceScreen(
    session: TowerSession,
    /** The goal: kept by the shell, so the talk button can write into it. */
    goal: String,
    onGoal: (String) -> Unit,
    onBack: () -> Unit,
    onLaunched: (space: String) -> Unit,
    /** A tower that can't launch got the goal in words: back to the queue for its reply. */
    onAsked: () -> Unit,
) {
    val link by session.link.collectAsStateWithLifecycle()
    val missionsOn by session.missionsSupported.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var setups by remember { mutableStateOf<List<Setup>?>(null) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val live = link is Link.Live
    val actions by session.actions.collectAsStateWithLifecycle()
    // The launch under way, by its action's id (kept across a rotation; its events keep it current).
    var launching by rememberSaveable { mutableStateOf<String?>(null) }
    val action = launching?.let { actions[it] }
    LaunchedEffect(action?.state) {
        if (action?.state == "done") {
            onGoal("")
            launching = null
            onLaunched(action.launch?.space?.ifBlank { null } ?: action.space)
        }
    }
    LaunchedEffect(live) {
        if (live) session.setups().onSuccess { list ->
            setups = list
            if (picked == null || list.none { it.name == picked }) picked = list.firstOrNull()?.name
        }.onFailure { setups = emptyList() }
    }
    val canLaunch = missionsOn == true
    val setup = setups?.firstOrNull { it.name == picked }
    // What launching would do, asked again when the goal rests or another setup is picked.
    var plan by remember { mutableStateOf<LaunchPlan?>(null) }
    var planError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(goal.trim(), picked, canLaunch, live) {
        val g = goal.trim()
        if (!canLaunch || !live || g.isEmpty()) {
            plan = null
            planError = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(LaunchModel.PLAN_AFTER_MS)
        session.launchPlan(g, picked)
            .onSuccess {
                plan = it
                planError = null
            }
            .onFailure {
                plan = null
                planError = TowerSession.describe(it)
            }
    }

    fun launch() {
        val g = goal.trim()
        if (g.isEmpty() || busy) return
        busy = true
        scope.launch {
            if (canLaunch) {
                session.launch(g, setup?.name)
                    .onSuccess { a -> launching = a.id }
                    .onFailure { snack("Couldn't launch: ${TowerSession.describe(it)}") }
            } else {
                session.ask(LaunchModel.words(g))
                    .onSuccess {
                        session.linger()
                        onGoal("")
                        onAsked()
                    }
                    .onFailure { snack("Couldn't send: ${TowerSession.describe(it)}") }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("New space", "From one sentence", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Goal")
            val shape = RoundedCornerShape(18.dp)
            BasicTextField(
                goal, onGoal,
                Modifier.fillMaxWidth().semantics { contentDescription = "The goal, in one sentence" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = tc.text),
                cursorBrush = SolidColor(tc.decisionInk),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                maxLines = 5,
                // The box is drawn inside the field, so a tap anywhere in it (not only on the words) starts typing.
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(shape).background(tc.cardStrong).border(1.dp, tc.edge, shape).heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
                        if (goal.isEmpty()) Text("Case exports as PDF", style = MaterialTheme.typography.bodyLarge, color = tc.faint)
                        inner()
                    }
                },
            )
            Text("Say it with the talk button, or type it.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint)

            if (canLaunch) {
                SectionLabel("Setup")
                val list = setups
                when {
                    list == null -> Text("Reading the saved setups…", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                    list.isEmpty() -> Text("No saved setups: the tower picks the sessions from the goal. Save one on the Mac with “tower setup add”.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = tc.muted)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { s -> Toggle(s.name, s.name == picked) { picked = s.name } }
                    }
                }
                SectionLabel("What the tower will do")
                val p = plan
                when {
                    action != null -> ActionCard(action)
                    p != null -> PlanCard(p)
                    planError != null -> Text(planError!!, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = tc.failed)
                    else -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tc.card).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LaunchModel.plan(setup).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = tc.muted) }
                    }
                }
            } else if (missionsOn == false) {
                Text(
                    "This Mac's tower can't launch a space by itself yet: the goal goes to the tower in words, and it opens the sessions.",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = tc.muted,
                )
            }
            Row(Modifier.padding(top = 4.dp)) {
                val running = action?.running == true
                PillButton(
                    if (busy || running) "Launching…" else if (action?.state == "failed") "Try again" else if (canLaunch) "Launch" else "Ask the tower",
                    primary = true, enabled = live && goal.isNotBlank() && !busy && !running,
                ) {
                    launching = null
                    launch()
                }
            }
            if (!live) Text("The Mac is out of reach.", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = tc.faint)
        }
    }
}
