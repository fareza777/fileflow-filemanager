package com.fileflow.app.domain.rules

import androidx.annotation.StringRes
import com.fileflow.app.R

enum class TemplateIcon { WHATSAPP, SCREENSHOT, APK, INVOICE, ARCHIVE }

/**
 * The rules people actually ask for on day one. Each one is deliberately
 * safe — they move files into a review folder rather than deleting anything,
 * so a first run can only ever tidy up.
 */
data class RuleTemplate(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: TemplateIcon,
    val build: (primaryRoot: String) -> RuleDraft,
)

object RuleTemplates {

    val all: List<RuleTemplate> = listOf(
        RuleTemplate(
            id = "whatsapp_pdf",
            titleRes = R.string.template_whatsapp_title,
            descriptionRes = R.string.template_whatsapp_desc,
            icon = TemplateIcon.WHATSAPP,
        ) { root ->
            RuleDraft(
                name = "WhatsApp PDFs → Work",
                spec = RuleSpec(
                    conditions = listOf(
                        MatchCondition.Extension(listOf("pdf")),
                        MatchCondition.SourceFolder(listOf("WhatsApp")),
                    ),
                    matchMode = MatchMode.ALL,
                    action = ActionSpec(
                        kind = ActionKind.MOVE,
                        destination = "$root/Documents/Work",
                    ),
                    schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 21, minute = 0),
                ),
            )
        },
        RuleTemplate(
            id = "old_screenshots",
            titleRes = R.string.template_screenshot_title,
            descriptionRes = R.string.template_screenshot_desc,
            icon = TemplateIcon.SCREENSHOT,
        ) { root ->
            RuleDraft(
                name = "Screenshots older than 90 days → Archive",
                spec = RuleSpec(
                    conditions = listOf(
                        MatchCondition.Name("screenshot", NameMode.CONTAINS),
                        MatchCondition.Age(olderThanDays = 90),
                    ),
                    matchMode = MatchMode.ALL,
                    action = ActionSpec(
                        kind = ActionKind.MOVE,
                        destination = "$root/Pictures/Archive",
                        subfolder = SubfolderStrategy.BY_MONTH,
                    ),
                    schedule = ScheduleSpec(
                        kind = ScheduleKind.WEEKLY,
                        hour = 20,
                        minute = 0,
                        daysOfWeek = listOf(7),
                    ),
                ),
            )
        },
        RuleTemplate(
            id = "old_apks",
            titleRes = R.string.template_apk_title,
            descriptionRes = R.string.template_apk_desc,
            icon = TemplateIcon.APK,
        ) { root ->
            RuleDraft(
                name = "APKs older than 30 days → Review",
                spec = RuleSpec(
                    conditions = listOf(
                        MatchCondition.Extension(listOf("apk", "apks", "xapk", "apkm")),
                        MatchCondition.Age(olderThanDays = 30),
                    ),
                    matchMode = MatchMode.ALL,
                    action = ActionSpec(
                        kind = ActionKind.MOVE,
                        destination = "$root/Download/Review/Installers",
                    ),
                    schedule = ScheduleSpec(
                        kind = ScheduleKind.WEEKLY,
                        hour = 20,
                        minute = 30,
                        daysOfWeek = listOf(6, 7),
                    ),
                ),
            )
        },
        RuleTemplate(
            id = "invoices",
            titleRes = R.string.template_invoice_title,
            descriptionRes = R.string.template_invoice_desc,
            icon = TemplateIcon.INVOICE,
        ) { root ->
            RuleDraft(
                name = "Invoices → Finance",
                spec = RuleSpec(
                    conditions = listOf(
                        MatchCondition.Extension(listOf("pdf", "jpg", "png")),
                        MatchCondition.Name("invoice", NameMode.CONTAINS),
                    ),
                    matchMode = MatchMode.ALL,
                    action = ActionSpec(
                        kind = ActionKind.MOVE,
                        destination = "$root/Documents/Finance",
                        subfolder = SubfolderStrategy.BY_MONTH,
                    ),
                    schedule = ScheduleSpec(kind = ScheduleKind.DAILY, hour = 20, minute = 30),
                ),
            )
        },
        RuleTemplate(
            id = "stale_downloads",
            titleRes = R.string.template_archive_title,
            descriptionRes = R.string.template_archive_desc,
            icon = TemplateIcon.ARCHIVE,
        ) { root ->
            RuleDraft(
                name = "Forgotten downloads → Archive",
                spec = RuleSpec(
                    conditions = listOf(
                        MatchCondition.SourceFolder(listOf("Download")),
                        MatchCondition.Age(olderThanDays = 180),
                        MatchCondition.Size(minBytes = 10L * 1024 * 1024),
                    ),
                    matchMode = MatchMode.ALL,
                    action = ActionSpec(
                        kind = ActionKind.MOVE,
                        destination = "$root/Download/Archive",
                        subfolder = SubfolderStrategy.BY_MONTH,
                    ),
                    schedule = ScheduleSpec(
                        kind = ScheduleKind.WEEKLY,
                        hour = 21,
                        minute = 0,
                        daysOfWeek = listOf(1),
                    ),
                ),
            )
        },
    )
}
