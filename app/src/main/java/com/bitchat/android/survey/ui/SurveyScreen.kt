package com.bitchat.android.survey.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.survey.*
import com.bitchat.android.ui.ChatViewModel

/**
 * Forms fork: full-screen Forms surface, shown as an overlay over the chat screen.
 * Self-contained navigation between Home / Build / Fill / Results.
 */

private sealed interface SurveyNav {
    object Home : SurveyNav
    object Build : SurveyNav
    data class Fill(val survey: Survey) : SurveyNav
    data class Results(val survey: Survey) : SurveyNav
}

@Composable
fun SurveyScreen(viewModel: ChatViewModel, onClose: () -> Unit) {
    val repo = viewModel.surveyRepository
    var nav by remember { mutableStateOf<SurveyNav>(SurveyNav.Home) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = nav) {
            is SurveyNav.Home -> SurveyHome(
                repo = repo,
                onClose = onClose,
                onNew = { nav = SurveyNav.Build },
                onFill = { nav = SurveyNav.Fill(it) },
                onResults = { nav = SurveyNav.Results(it) }
            )
            is SurveyNav.Build -> SurveyBuilder(repo = repo, onDone = { nav = SurveyNav.Home })
            is SurveyNav.Fill -> SurveyFill(repo = repo, survey = s.survey, onDone = { nav = SurveyNav.Home })
            is SurveyNav.Results -> SurveyResults(repo = repo, survey = s.survey, onBack = { nav = SurveyNav.Home })
        }
    }
}

@Composable
private fun Header(title: String, backLabel: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(backLabel) }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            if (trailing != null) trailing()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
private fun SurveyHome(
    repo: SurveyRepository,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onFill: (Survey) -> Unit,
    onResults: (Survey) -> Unit
) {
    val received by repo.receivedSurveys.collectAsStateWithLifecycle()
    val mine by repo.mySurveys.collectAsStateWithLifecycle()
    val answered by repo.answered.collectAsStateWithLifecycle()
    val responses by repo.responses.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(title = "Forms", backLabel = "✕  Close", onBack = onClose, trailing = {
            Button(onClick = onNew) { Text("＋ New") }
        })
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Available (${received.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Mine (${mine.size})") })
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (tab == 0) {
                if (received.isEmpty()) {
                    EmptyHint("No surveys received yet.\nWhen a nearby peer publishes one over the mesh, it appears here.")
                }
                received.forEach { survey ->
                    val done = answered.contains(survey.id)
                    SurveyCard(
                        title = survey.title,
                        subtitle = "by ${survey.creatorNickname} · ${survey.questions.size} questions" +
                            (if (survey.closed) " · CLOSED" else "") + (if (done) " · ✓ submitted" else ""),
                        buttonLabel = if (survey.closed || done) "View" else "Fill out",
                        onClick = { onFill(survey) }
                    )
                }
            } else {
                if (mine.isEmpty()) {
                    EmptyHint("You haven't created any surveys.\nTap ＋ New to build one and publish it over the mesh.")
                }
                mine.forEach { survey ->
                    val count = (responses[survey.id] ?: emptyList()).size
                    SurveyCard(
                        title = survey.title,
                        subtitle = "$count responses" + (if (survey.closed) " · CLOSED" else ""),
                        buttonLabel = "Results",
                        onClick = { onResults(survey) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

@Composable
private fun SurveyCard(title: String, subtitle: String, buttonLabel: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            OutlinedButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

// ---------------------------------------------------------------------------
// Builder
// ---------------------------------------------------------------------------

private data class QuestionDraft(
    val text: String = "",
    val type: QuestionType = QuestionType.SHORT_TEXT,
    val optionsText: String = "",
    val required: Boolean = false
)

@Composable
private fun SurveyBuilder(repo: SurveyRepository, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val drafts = remember { mutableStateListOf(QuestionDraft()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(title = "New Survey", backLabel = "←  Cancel", onBack = onDone)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Survey title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            drafts.forEachIndexed { index, draft ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Question ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            if (drafts.size > 1) {
                                TextButton(onClick = { drafts.removeAt(index) }) { Text("🗑 Remove") }
                            }
                        }
                        OutlinedTextField(
                            value = draft.text,
                            onValueChange = { drafts[index] = draft.copy(text = it) },
                            label = { Text("Question text") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TypePicker(selected = draft.type, onSelect = { drafts[index] = draft.copy(type = it) })
                        if (draft.type == QuestionType.SINGLE_CHOICE || draft.type == QuestionType.MULTI_CHOICE) {
                            OutlinedTextField(
                                value = draft.optionsText,
                                onValueChange = { drafts[index] = draft.copy(optionsText = it) },
                                label = { Text("Options (one per line)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = draft.required, onCheckedChange = { drafts[index] = draft.copy(required = it) })
                            Text("Required", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            OutlinedButton(onClick = { drafts.add(QuestionDraft()) }, modifier = Modifier.fillMaxWidth()) {
                Text("＋ Add question")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        val canPublish = title.isNotBlank() && drafts.any { it.text.isNotBlank() }
        Button(
            onClick = {
                val questions = drafts.filter { it.text.isNotBlank() }.mapIndexed { i, d ->
                    SurveyQuestion(
                        id = "q${i + 1}",
                        text = d.text.trim(),
                        type = d.type,
                        options = if (d.type == QuestionType.SINGLE_CHOICE || d.type == QuestionType.MULTI_CHOICE)
                            d.optionsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
                        required = d.required
                    )
                }
                repo.publish(title.trim(), description.trim(), questions)
                onDone()
            },
            enabled = canPublish,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) { Text("Publish over mesh") }
    }
}

@Composable
private fun TypePicker(selected: QuestionType, onSelect: (QuestionType) -> Unit) {
    val types = listOf(
        QuestionType.SHORT_TEXT to "Short text",
        QuestionType.LONG_TEXT to "Paragraph",
        QuestionType.SINGLE_CHOICE to "Single choice",
        QuestionType.MULTI_CHOICE to "Multi choice",
        QuestionType.SCALE to "Scale 1–5"
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        types.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (type, label) ->
                    val on = selected == type
                    if (on) {
                        Button(onClick = { onSelect(type) }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                    } else {
                        OutlinedButton(onClick = { onSelect(type) }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Fill out
// ---------------------------------------------------------------------------

@Composable
private fun SurveyFill(repo: SurveyRepository, survey: Survey, onDone: () -> Unit) {
    val answered by repo.answered.collectAsStateWithLifecycle()
    val alreadyDone = answered.contains(survey.id)
    val readOnly = survey.closed || alreadyDone

    val textAnswers = remember { mutableStateMapOf<String, String>() }
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(title = survey.title, backLabel = "←  Back", onBack = onDone)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (survey.description.isNotBlank()) {
                Text(survey.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (readOnly) {
                Text(
                    if (survey.closed) "This survey is closed." else "You have already submitted a response.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            survey.questions.forEach { q ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            q.text + if (q.required) " *" else "",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        when (q.type) {
                            QuestionType.SHORT_TEXT, QuestionType.LONG_TEXT -> {
                                OutlinedTextField(
                                    value = textAnswers[q.id] ?: "",
                                    onValueChange = { textAnswers[q.id] = it },
                                    enabled = !readOnly,
                                    singleLine = q.type == QuestionType.SHORT_TEXT,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            QuestionType.SINGLE_CHOICE -> {
                                q.options.forEach { opt ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = textAnswers[q.id] == opt,
                                            enabled = !readOnly,
                                            onClick = { textAnswers[q.id] = opt }
                                        )
                                        Text(opt)
                                    }
                                }
                            }
                            QuestionType.MULTI_CHOICE -> {
                                q.options.forEach { opt ->
                                    val set = multiAnswers[q.id] ?: emptySet()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = set.contains(opt),
                                            enabled = !readOnly,
                                            onCheckedChange = { checked ->
                                                multiAnswers[q.id] = if (checked) set + opt else set - opt
                                            }
                                        )
                                        Text(opt)
                                    }
                                }
                            }
                            QuestionType.SCALE -> {
                                val current = textAnswers[q.id]?.toFloatOrNull() ?: q.scaleMin.toFloat()
                                Text("Value: ${current.toInt()}")
                                Slider(
                                    value = current,
                                    onValueChange = { textAnswers[q.id] = it.toInt().toString() },
                                    valueRange = q.scaleMin.toFloat()..q.scaleMax.toFloat(),
                                    steps = (q.scaleMax - q.scaleMin - 1).coerceAtLeast(0),
                                    enabled = !readOnly
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!readOnly) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Button(
                onClick = {
                    val answers = survey.questions.mapNotNull { q ->
                        val values: List<String> = when (q.type) {
                            QuestionType.MULTI_CHOICE -> (multiAnswers[q.id] ?: emptySet()).toList()
                            else -> textAnswers[q.id]?.let { if (it.isBlank()) emptyList() else listOf(it) } ?: emptyList()
                        }
                        if (values.isEmpty()) null else SurveyAnswer(q.id, values)
                    }
                    repo.submit(survey, answers)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) { Text("Submit response") }
        }
    }
}

// ---------------------------------------------------------------------------
// Results (creator)
// ---------------------------------------------------------------------------

@Composable
private fun SurveyResults(repo: SurveyRepository, survey: Survey, onBack: () -> Unit) {
    val responsesMap by repo.responses.collectAsStateWithLifecycle()
    val mine by repo.mySurveys.collectAsStateWithLifecycle()
    val current = mine.firstOrNull { it.id == survey.id } ?: survey
    val responses = responsesMap[survey.id] ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        Header(title = "Results", backLabel = "←  Back", onBack = onBack, trailing = {
            if (!current.closed) TextButton(onClick = { repo.close(survey.id) }) { Text("Close") }
        })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(current.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${responses.size} responses" + if (current.closed) " · CLOSED" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (responses.isEmpty()) {
                EmptyHint("No responses yet. Responses from peers arrive over the mesh and appear here automatically.")
            }
            current.questions.forEach { q ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(q.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val values = responses.flatMap { r -> r.answers.firstOrNull { it.questionId == q.id }?.values ?: emptyList() }
                        when (q.type) {
                            QuestionType.SINGLE_CHOICE, QuestionType.MULTI_CHOICE, QuestionType.SCALE -> {
                                val counts = values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                                if (counts.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                counts.forEach { (value, count) ->
                                    Text("• $value — $count", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            QuestionType.SHORT_TEXT, QuestionType.LONG_TEXT -> {
                                if (values.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                values.forEach { v ->
                                    Text("• $v", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
