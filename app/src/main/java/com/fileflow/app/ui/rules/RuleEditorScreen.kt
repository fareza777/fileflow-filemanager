package com.fileflow.app.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fileflow.app.R
import com.fileflow.app.core.model.FileCategory
import com.fileflow.app.core.storage.ConflictPolicy
import com.fileflow.app.domain.rules.ActionKind
import com.fileflow.app.domain.rules.MatchCondition
import com.fileflow.app.domain.rules.MatchMode
import com.fileflow.app.domain.rules.NameMode
import com.fileflow.app.domain.rules.ScheduleKind
import com.fileflow.app.domain.rules.SubfolderStrategy
import com.fileflow.app.ui.components.FolderPickerSheet
import com.fileflow.app.ui.components.SectionCard
import com.fileflow.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: RuleEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val spec = state.draft.spec

    var showDestinationPicker by remember { mutableStateOf(false) }
    var showRootPicker by remember { mutableStateOf(false) }
    var showAddCondition by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved(state.savedId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.rule_new_title else R.string.rule_edit_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                state.error?.let { key ->
                    Text(
                        text = validationMessage(key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = viewModel::saveAndPreview,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rule_save_and_preview))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.draft.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.rule_name)) },
                    placeholder = { Text(stringResource(R.string.rule_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionHeader(stringResource(R.string.rule_scope)) }
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.rule_scope_all),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = viewModel.isWholeStorage(),
                                onCheckedChange = viewModel::setWholeStorage,
                            )
                        }
                        if (!viewModel.isWholeStorage()) {
                            spec.roots.forEach { root ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = root,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { viewModel.removeRoot(root) }) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(
                                onClick = { showRootPicker = true },
                                label = { Text(stringResource(R.string.rule_add_folder)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.rule_recursive),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = spec.recursive,
                                onCheckedChange = viewModel::setRecursive,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.rule_skip_hidden),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = spec.skipHidden,
                                onCheckedChange = viewModel::setSkipHidden,
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.rule_when),
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = spec.matchMode == MatchMode.ALL,
                                onClick = { viewModel.setMatchMode(MatchMode.ALL) },
                                label = { Text(stringResource(R.string.rule_match_all)) },
                            )
                            FilterChip(
                                selected = spec.matchMode == MatchMode.ANY,
                                onClick = { viewModel.setMatchMode(MatchMode.ANY) },
                                label = { Text(stringResource(R.string.rule_match_any)) },
                            )
                        }
                    },
                )
            }

            items(spec.conditions.size, key = { index -> "condition-$index" }) { index ->
                ConditionCard(
                    condition = spec.conditions[index],
                    onChange = { viewModel.updateCondition(index, it) },
                    onRemove = { viewModel.removeCondition(index) },
                )
            }

            item {
                TextButton(onClick = { showAddCondition = !showAddCondition }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.rule_add_condition))
                }
                if (showAddCondition) {
                    SectionCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ConditionKindPicker { condition ->
                                viewModel.addCondition(condition)
                                showAddCondition = false
                            }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.rule_then)) }
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.rule_action),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionKind.entries.forEach { kind ->
                                FilterChip(
                                    selected = spec.action.kind == kind,
                                    onClick = { viewModel.setActionKind(kind) },
                                    label = { Text(labelForAction(kind)) },
                                )
                            }
                        }

                        if (spec.action.kind == ActionKind.MOVE ||
                            spec.action.kind == ActionKind.COPY
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.rule_destination),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = spec.action.destination
                                            ?: stringResource(R.string.rule_destination_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                TextButton(onClick = { showDestinationPicker = true }) {
                                    Text(stringResource(R.string.action_edit))
                                }
                            }
                            Text(
                                text = stringResource(R.string.rule_on_conflict),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ConflictPolicy.entries.forEach { policy ->
                                    FilterChip(
                                        selected = spec.action.conflictPolicy == policy,
                                        onClick = { viewModel.setConflictPolicy(policy) },
                                        label = { Text(labelForConflict(policy)) },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.rule_destination),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SubfolderStrategy.entries.forEach { strategy ->
                                    FilterChip(
                                        selected = spec.action.subfolder == strategy,
                                        onClick = { viewModel.setSubfolder(strategy) },
                                        label = { Text(labelForSubfolder(strategy)) },
                                    )
                                }
                            }
                        }

                        if (spec.action.kind == ActionKind.RENAME) {
                            OutlinedTextField(
                                value = spec.action.renamePattern.orEmpty(),
                                onValueChange = viewModel::setRenamePattern,
                                label = { Text(stringResource(R.string.action_kind_rename)) },
                                placeholder = { Text("{date} {name}") },
                                supportingText = { Text("{name} {date} {time} {ext} {parent}") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (spec.action.kind == ActionKind.DELETE) {
                            Text(
                                text = stringResource(R.string.action_delete_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.rule_schedule)) }
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ScheduleKind.entries.forEach { kind ->
                                FilterChip(
                                    selected = spec.schedule.kind == kind,
                                    onClick = { viewModel.setScheduleKind(kind) },
                                    label = { Text(labelForSchedule(kind)) },
                                )
                            }
                        }

                        if (spec.schedule.kind == ScheduleKind.DAILY ||
                            spec.schedule.kind == ScheduleKind.WEEKLY
                        ) {
                            TimePickerRow(
                                hour = spec.schedule.hour,
                                minute = spec.schedule.minute,
                                onChange = viewModel::setScheduleTime,
                            )
                        }

                        if (spec.schedule.kind == ScheduleKind.WEEKLY) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                (1..7).forEach { day ->
                                    FilterChip(
                                        selected = day in spec.schedule.daysOfWeek,
                                        onClick = { viewModel.toggleScheduleDay(day) },
                                        label = { Text(dayLabel(day)) },
                                    )
                                }
                            }
                        }

                        SwitchRow(
                            label = stringResource(R.string.rule_wifi_only),
                            checked = spec.schedule.wifiOnly,
                            onChange = viewModel::setWifiOnly,
                        )
                        SwitchRow(
                            label = stringResource(R.string.rule_charging_only),
                            checked = spec.schedule.chargingOnly,
                            onChange = viewModel::setChargingOnly,
                        )
                        SwitchRow(
                            label = stringResource(R.string.rules_enable_notifications),
                            checked = spec.notify,
                            onChange = viewModel::setNotify,
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (showDestinationPicker) {
        FolderPickerSheet(
            startPath = spec.action.destination ?: viewModel.defaultRoot(),
            title = stringResource(R.string.rule_destination),
            onDismiss = { showDestinationPicker = false },
            onPick = {
                viewModel.setDestination(it)
                showDestinationPicker = false
            },
        )
    }

    if (showRootPicker) {
        FolderPickerSheet(
            startPath = viewModel.defaultRoot(),
            title = stringResource(R.string.rule_add_folder),
            onDismiss = { showRootPicker = false },
            onPick = {
                viewModel.addRoot(it)
                viewModel.setWholeStorage(false)
                showRootPicker = false
            },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TimePickerRow(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    var hourText by remember(hour) { mutableStateOf(hour.toString().padStart(2, '0')) }
    var minuteText by remember(minute) { mutableStateOf(minute.toString().padStart(2, '0')) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.rule_time),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = hourText,
            onValueChange = { value ->
                hourText = value.filter { it.isDigit() }.take(2)
                onChange(hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0, minute)
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            modifier = Modifier.width(72.dp),
        )
        Text(":", modifier = Modifier.padding(horizontal = 6.dp))
        OutlinedTextField(
            value = minuteText,
            onValueChange = { value ->
                minuteText = value.filter { it.isDigit() }.take(2)
                onChange(hour, minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0)
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun ConditionKindPicker(onPick: (MatchCondition) -> Unit) {
    val options: List<Pair<Int, () -> MatchCondition>> = listOf(
        R.string.cond_name to { MatchCondition.Name("") },
        R.string.cond_extension to { MatchCondition.Extension(emptyList()) },
        R.string.cond_category to { MatchCondition.Category(emptyList()) },
        R.string.cond_folder to { MatchCondition.SourceFolder(emptyList()) },
        R.string.cond_size to { MatchCondition.Size() },
        R.string.cond_age to { MatchCondition.Age() },
    )
    options.forEach { (labelRes, factory) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onPick(factory()) }
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun labelForAction(kind: ActionKind): String = when (kind) {
    ActionKind.MOVE -> stringResource(R.string.action_kind_move)
    ActionKind.COPY -> stringResource(R.string.action_kind_copy)
    ActionKind.TRASH -> stringResource(R.string.action_kind_trash)
    ActionKind.DELETE -> stringResource(R.string.action_kind_delete)
    ActionKind.RENAME -> stringResource(R.string.action_kind_rename)
}

@Composable
private fun labelForConflict(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.RENAME -> stringResource(R.string.conflict_rename)
    ConflictPolicy.OVERWRITE -> stringResource(R.string.conflict_overwrite)
    ConflictPolicy.SKIP -> stringResource(R.string.conflict_skip)
}

@Composable
private fun labelForSubfolder(strategy: SubfolderStrategy): String = when (strategy) {
    SubfolderStrategy.NONE -> stringResource(R.string.common_none)
    SubfolderStrategy.BY_CATEGORY -> stringResource(R.string.cond_category)
    SubfolderStrategy.BY_MONTH -> stringResource(R.string.search_date)
    SubfolderStrategy.BY_EXTENSION -> stringResource(R.string.cond_extension)
}

@Composable
private fun labelForSchedule(kind: ScheduleKind): String = when (kind) {
    ScheduleKind.OFF -> stringResource(R.string.rule_schedule_off)
    ScheduleKind.HOURLY -> stringResource(R.string.rule_schedule_hourly)
    ScheduleKind.DAILY -> stringResource(R.string.rule_schedule_daily)
    ScheduleKind.WEEKLY -> stringResource(R.string.rule_schedule_weekly)
}

@Composable
private fun validationMessage(key: String): String = when (key) {
    "name" -> stringResource(R.string.rule_validation_name)
    "condition" -> stringResource(R.string.rule_validation_condition)
    "value" -> stringResource(R.string.cond_value_hint)
    "destination" -> stringResource(R.string.rule_validation_destination)
    else -> stringResource(R.string.common_error)
}

private fun dayLabel(day: Int): String = when (day) {
    1 -> "Mo"; 2 -> "Tu"; 3 -> "We"; 4 -> "Th"; 5 -> "Fr"; 6 -> "Sa"; else -> "Su"
}

@Composable
private fun CategoryChips(
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FileCategory.entries.forEach { category ->
            FilterChip(
                selected = category.key in selected,
                onClick = { onToggle(category.key) },
                label = { Text(category.key) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameModeDropdown(current: NameMode, onChange: (NameMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labelForNameMode(current),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.cond_name)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            NameMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(labelForNameMode(mode)) },
                    onClick = {
                        onChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun labelForNameMode(mode: NameMode): String = when (mode) {
    NameMode.CONTAINS -> stringResource(R.string.cond_name_contains)
    NameMode.STARTS_WITH -> stringResource(R.string.cond_name_starts)
    NameMode.ENDS_WITH -> stringResource(R.string.cond_name_ends)
    NameMode.WILDCARD -> stringResource(R.string.cond_name_wildcard)
    NameMode.REGEX -> stringResource(R.string.cond_name_regex)
}

@Composable
private fun ConditionCard(
    condition: MatchCondition,
    onChange: (MatchCondition) -> Unit,
    onRemove: () -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conditionTitle(condition),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                }
            }

            when (condition) {
                is MatchCondition.Name -> {
                    OutlinedTextField(
                        value = condition.text,
                        onValueChange = { onChange(condition.copy(text = it)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NameModeDropdown(current = condition.mode) {
                        onChange(condition.copy(mode = it))
                    }
                }

                is MatchCondition.Extension -> {
                    OutlinedTextField(
                        value = condition.extensions.joinToString(", "),
                        onValueChange = { value ->
                            onChange(
                                condition.copy(
                                    extensions = value.split(',', ' ')
                                        .map { it.trim().removePrefix(".").lowercase() }
                                        .filter { it.isNotEmpty() }
                                )
                            )
                        },
                        placeholder = { Text(stringResource(R.string.cond_extension_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is MatchCondition.Category -> CategoryChips(
                    selected = condition.categories,
                    onToggle = { key ->
                        val next = if (key in condition.categories) {
                            condition.categories - key
                        } else {
                            condition.categories + key
                        }
                        onChange(condition.copy(categories = next))
                    },
                )

                is MatchCondition.SourceFolder -> {
                    OutlinedTextField(
                        value = condition.folders.joinToString(", "),
                        onValueChange = { value ->
                            onChange(
                                condition.copy(
                                    folders = value.split(',')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                )
                            )
                        },
                        placeholder = { Text(stringResource(R.string.cond_folder_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is MatchCondition.Size -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = condition.minBytes?.let { (it / 1024 / 1024).toString() } ?: "",
                            onValueChange = { value ->
                                onChange(
                                    condition.copy(
                                        minBytes = value.toLongOrNull()?.let { it * 1024 * 1024 }
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.cond_size_min)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = condition.maxBytes?.let { (it / 1024 / 1024).toString() } ?: "",
                            onValueChange = { value ->
                                onChange(
                                    condition.copy(
                                        maxBytes = value.toLongOrNull()?.let { it * 1024 * 1024 }
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.cond_size_max)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MatchCondition.Age -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = condition.olderThanDays?.toString() ?: "",
                            onValueChange = { value ->
                                onChange(condition.copy(olderThanDays = value.toIntOrNull()))
                            },
                            label = { Text(stringResource(R.string.cond_age_older)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = condition.newerThanDays?.toString() ?: "",
                            onValueChange = { value ->
                                onChange(condition.copy(newerThanDays = value.toIntOrNull()))
                            },
                            label = { Text(stringResource(R.string.cond_age_newer)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.cond_negate),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = condition.negate,
                    onCheckedChange = { negate -> onChange(condition.withNegate(negate)) },
                )
            }
        }
    }
}

@Composable
private fun conditionTitle(condition: MatchCondition): String = when (condition) {
    is MatchCondition.Name -> stringResource(R.string.cond_name)
    is MatchCondition.Extension -> stringResource(R.string.cond_extension)
    is MatchCondition.Category -> stringResource(R.string.cond_category)
    is MatchCondition.SourceFolder -> stringResource(R.string.cond_folder)
    is MatchCondition.Size -> stringResource(R.string.cond_size)
    is MatchCondition.Age -> stringResource(R.string.cond_age)
}

private fun MatchCondition.withNegate(negate: Boolean): MatchCondition = when (this) {
    is MatchCondition.Name -> copy(negate = negate)
    is MatchCondition.Extension -> copy(negate = negate)
    is MatchCondition.Category -> copy(negate = negate)
    is MatchCondition.SourceFolder -> copy(negate = negate)
    is MatchCondition.Size -> copy(negate = negate)
    is MatchCondition.Age -> copy(negate = negate)
}