package com.fileflow.app.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fileflow.app.R
import com.fileflow.app.core.util.Formatters
import com.fileflow.app.data.repo.BrowseRepository
import com.fileflow.app.data.repo.RulesRepository
import com.fileflow.app.domain.rules.ExecutionProgress
import com.fileflow.app.domain.rules.MatchCondition
import com.fileflow.app.domain.rules.NameMode
import com.fileflow.app.domain.rules.Rule
import com.fileflow.app.domain.rules.RuleEngine
import com.fileflow.app.domain.rules.RuleTemplates
import com.fileflow.app.domain.rules.ScheduleCalculator
import com.fileflow.app.domain.rules.TemplateIcon
import com.fileflow.app.ui.components.ConfirmDialog
import com.fileflow.app.ui.components.EmptyState
import com.fileflow.app.ui.components.ProgressPanel
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader
import com.fileflow.app.ui.util.LocalUserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RulesState(
    val rules: List<Rule> = emptyList(),
    val runningRuleId: Long? = null,
    val progress: ExecutionProgress? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val repository: RulesRepository,
    private val browseRepository: BrowseRepository,
    private val engine: RuleEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(RulesState())
    val state: StateFlow<RulesState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeRules().collect { rules ->
                _state.update { it.copy(rules = rules, loading = false) }
            }
        }
    }

    fun setEnabled(rule: Rule, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(rule.id, enabled) }
    }

    fun delete(rule: Rule) {
        viewModelScope.launch {
            repository.delete(rule.id)
            _messages.send("Rule deleted")
        }
    }

    fun runNow(rule: Rule) {
        if (_state.value.runningRuleId != null) return
        viewModelScope.launch {
            _state.update { it.copy(runningRuleId = rule.id, progress = null) }
            val outcome = repository.runNow(rule) { progress ->
                _state.update { it.copy(progress = progress) }
            }
            _state.update { it.copy(runningRuleId = null, progress = null) }
            _messages.send(
                when {
                    outcome.cancelled -> "Stopped"
                    outcome.succeeded == 0 && outcome.failed == 0 -> "Nothing to do"
                    outcome.failed > 0 -> "${outcome.succeeded} done, ${outcome.failed} failed"
                    else -> "${outcome.succeeded} files organised"
                }
            )
        }
    }

    fun useTemplate(templateId: String) {
        val template = RuleTemplates.all.firstOrNull { it.id == templateId } ?: return
        viewModelScope.launch {
            val draft = template.build(browseRepository.primaryRoot())
            val existing = _state.value.rules.firstOrNull { it.name == draft.name }
            if (existing != null) {
                _messages.send("That rule already exists")
                return@launch
            }
            repository.save(draft)
            _messages.send("Rule added")
        }
    }

    fun describe(rule: Rule): String = engine.describe(rule.spec)

    fun scheduleSummary(rule: Rule): String =
        if (rule.spec.schedule.isScheduled) {
            ScheduleCalculator.describe(rule.spec.schedule)
        } else {
            ""
        }

    /**
     * "Next: today 21:00" answers the question people actually have about a
     * scheduled rule — the interval alone does not.
     */
    fun nextRunLabel(rule: Rule): String? {
        val next = ScheduleCalculator.nextRunAt(rule.spec.schedule) ?: return null
        val now = System.currentTimeMillis()
        val delta = next - now
        return when {
            delta < 60_000 -> "in under a minute"
            delta < 3_600_000 -> "in ${delta / 60_000} min"
            delta < 86_400_000 -> "today ${Formatters.time(next)}"
            delta < 172_800_000 -> "tomorrow ${Formatters.time(next)}"
            else -> Formatters.dateTime(next)
        }
    }

    fun chipsFor(rule: Rule): List<String> = rule.spec.conditions.map { condition ->
        when (condition) {
            is MatchCondition.Name -> when (condition.mode) {
                NameMode.WILDCARD -> condition.text
                NameMode.REGEX -> "/${condition.text}/"
                else -> "\"${condition.text}\""
            }
            is MatchCondition.Extension ->
                condition.extensions.joinToString("/") { it.uppercase() }
            is MatchCondition.Category ->
                condition.categories.joinToString("/") { key ->
                    key.replaceFirstChar(Char::uppercase)
                }
            is MatchCondition.SourceFolder ->
                condition.folders.joinToString("/") { it.substringAfterLast('/').ifEmpty { it } }
            is MatchCondition.Size -> when {
                condition.minBytes != null && condition.maxBytes != null ->
                    "${Formatters.bytes(condition.minBytes)}–${Formatters.bytes(condition.maxBytes)}"
                condition.minBytes != null -> "> ${Formatters.bytes(condition.minBytes)}"
                condition.maxBytes != null -> "< ${Formatters.bytes(condition.maxBytes)}"
                else -> "any size"
            }
            is MatchCondition.Age -> when {
                condition.olderThanDays != null && condition.newerThanDays != null ->
                    "${condition.newerThanDays}–${condition.olderThanDays}d"
                condition.olderThanDays != null -> "> ${condition.olderThanDays} days"
                condition.newerThanDays != null -> "< ${condition.newerThanDays} days"
                else -> "any age"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    onCreateRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onPreviewRule: (Long) -> Unit,
    onHistory: (Long?) -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = LocalUserSettings.current
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<Rule?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onHistory(null) }) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = stringResource(R.string.cd_history),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateRule,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.rules_new)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.runningRuleId?.let { ruleId ->
                item {
                    val rule = state.rules.firstOrNull { it.id == ruleId }
                    ProgressPanel(
                        title = rule?.name ?: stringResource(R.string.common_loading),
                        detail = state.progress?.let {
                            "${it.currentName} · ${it.processed}/${it.total}"
                        },
                        fraction = state.progress?.let { progress ->
                            if (progress.total > 0) {
                                progress.processed.toFloat() / progress.total.toFloat()
                            } else {
                                null
                            }
                        },
                    )
                }
            }

            if (state.rules.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        icon = Icons.Filled.AutoAwesome,
                        title = stringResource(R.string.rules_empty),
                        body = stringResource(R.string.rules_empty_hint),
                    )
                }
            }

            items(state.rules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    description = viewModel.describe(rule),
                    schedule = viewModel.scheduleSummary(rule),
                    nextRun = viewModel.nextRunLabel(rule),
                    chips = viewModel.chipsFor(rule),
                    running = state.runningRuleId == rule.id,
                    onToggle = { viewModel.setEnabled(rule, it) },
                    // "Always preview before running" is a promise the app makes
                    // on the Settings screen; honouring it here is what makes the
                    // switch mean something.
                    onRun = {
                        if (settings.previewRequired) onPreviewRule(rule.id)
                        else viewModel.runNow(rule)
                    },
                    onPreview = { onPreviewRule(rule.id) },
                    onEdit = { onEditRule(rule.id) },
                    onHistory = { onHistory(rule.id) },
                    onDelete = { deleteTarget = rule },
                )
            }

            item { SectionHeader(stringResource(R.string.rules_templates)) }
            items(RuleTemplates.all, key = { it.id }) { template ->
                TemplateCard(
                    title = stringResource(template.titleRes),
                    description = stringResource(template.descriptionRes),
                    icon = template.icon,
                    onClick = { viewModel.useTemplate(template.id) },
                )
            }

            item { Spacer(Modifier.size(16.dp)) }
        }
    }

    deleteTarget?.let { rule ->
        ConfirmDialog(
            title = stringResource(R.string.rules_delete_title),
            body = stringResource(R.string.rules_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(rule)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    rule: Rule,
    description: String,
    schedule: String,
    nextRun: String?,
    chips: List<String>,
    running: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // A rule whose spec no longer decodes has silently stopped
                    // doing anything. Saying so is the whole point of keeping it.
                    if (rule.specUnreadable) {
                        Text(
                            text = stringResource(R.string.rules_unreadable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    enabled = !running,
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rules_preview)) },
                            onClick = { menu = false; onPreview() },
                            leadingIcon = {
                                Icon(Icons.Filled.Visibility, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            onClick = { menu = false; onEdit() },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rules_history)) },
                            onClick = { menu = false; onHistory() },
                            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { menu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        )
                    }
                }
            }

            if (chips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chips.take(4).forEach { chip -> Pill(chip, accent = true) }
                    if (chips.size > 4) Pill("+${chips.size - 4}")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (schedule.isNotBlank()) Pill(schedule)
                nextRun?.let { Pill("Next: $it") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (rule.lastRunAt != null) {
                        stringResource(R.string.rules_last_run, Formatters.relative(rule.lastRunAt))
                    } else {
                        stringResource(R.string.rules_never_run)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = onRun,
                    enabled = !running && rule.enabled,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 6.dp,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.rules_run_now),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, accent: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (accent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                }
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun TemplateCard(
    title: String,
    description: String,
    icon: TemplateIcon,
    onClick: () -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconForTemplate(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun iconForTemplate(icon: TemplateIcon): ImageVector = when (icon) {
    TemplateIcon.WHATSAPP -> Icons.Filled.PictureAsPdf
    TemplateIcon.SCREENSHOT -> Icons.Filled.Screenshot
    TemplateIcon.APK -> Icons.Filled.Android
    TemplateIcon.INVOICE -> Icons.Filled.Receipt
    TemplateIcon.ARCHIVE -> Icons.Filled.Archive
}