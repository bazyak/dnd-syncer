package com.bazyak.dndsyncer.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bazyak.dndsyncer.core.Access
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.LogSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bazyak.dndsyncer.core.Shell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                },
            ) { WelcomeScreen() }
        }
    }
}

@Composable
private fun WelcomeScreen() {
    val context = LocalContext.current
    var a11y by remember {
        mutableStateOf(Access.isAccessibilityEnabled(context, PhoneSyncService::class.java))
    }
    var rules by remember { mutableStateOf("") }
    var root by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        a11y = Access.isAccessibilityEnabled(context, PhoneSyncService::class.java)
        rules = PhoneNight.dump(context)
        root = Shell.isAvailable()
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DND syncer", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Держит «Не беспокоить» и ночной режим одинаковыми с часами. " +
                    "Театр на часах включает «Не беспокоить» здесь.",
                style = MaterialTheme.typography.bodyMedium,
            )

            PermissionCard(
                title = "Специальные возможности",
                description = "Держит приложение активным. Содержимое экрана " +
                    "и уведомления не читаются.",
                granted = a11y,
                onGrant = {
                    if (!Access.open(context, Access.accessibilityIntents())) {
                        Toast.makeText(context, "Экран недоступен", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            PermissionCard(
                title = "Root",
                description = "Режимы ставятся от имени системы: иначе не выключить " +
                    "«Не беспокоить», включённый вручную, и не переключить ночной " +
                    "режим Digital Wellbeing.",
                granted = root,
                onGrant = {
                    root = Shell.isAvailable()
                    if (!root) {
                        Toast.makeText(context, "Root недоступен", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            SyncModesCard()

            LogCard()

            // Диагностика в самом низу: нужна редко, а места занимает много.
            if (rules.isNotEmpty()) {
                Text("Состояние режимов", style = MaterialTheme.typography.titleSmall)
                Text(rules, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (granted) "Выдано" else "Не выдано",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!granted) Button(onClick = onGrant) { Text("Разрешить") }
            }
        }
    }
}

@Composable
private fun LogCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(LogSettings.isEnabled(context)) }
    var path by remember { mutableStateOf(PhoneApp.store?.describe().orEmpty()) }

    // Системный выбор папки. Постоянный доступ нужен, чтобы писать и после
    // перезагрузки: без takePersistableUriPermission разрешение живёт
    // до конца процесса.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            LogSettings.setFolder(context, uri)
            path = PhoneApp.store?.describe().orEmpty()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Писать лог", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        LogSettings.setEnabled(context, it)
                    },
                )
            }

            Text(path, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickFolder.launch(null) }) { Text("Папка") }

                if (LogSettings.folder(context) != null) {
                    Button(onClick = {
                        LogSettings.setFolder(context, null)
                        path = PhoneApp.store?.describe().orEmpty()
                    }) { Text("По умолчанию") }
                }
            }

            Button(
                onClick = {
                    Thread { PhoneNight.logSnapshot(context) }.start()
                    Toast.makeText(context, "Срез записан", Toast.LENGTH_SHORT).show()
                },
                enabled = enabled,
            ) { Text("Записать срез в лог") }
        }
    }
}

/** Порядок групп в списке: системное, Wellbeing, всё остальное. */
private fun groupOf(rule: ZenDump.Rule): Int = when {
    rule.isManual -> 0
    rule.pkg == "com.google.android.apps.wellbeing" -> 1
    else -> 2
}

/**
 * Выбор синхронизируемых режимов. На часах фильтров нет — там DND глушит
 * всё подряд, поэтому режимы вроде "Вождения", пропускающие часть звонков,
 * по умолчанию не уезжают.
 */
@Composable
private fun SyncModesCard() {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(emptyList<ZenDump.Rule>()) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        rules = withContext(Dispatchers.IO) {
            ZenDump.read(context)?.allRules.orEmpty()
                .filter { it.isManual || it.silences }
                // Сначала системное и Wellbeing (глушат телефон целиком),
                // затем режимы сервисов Google (пропускают часть звонков).
                .sortedWith(compareBy({ groupOf(it) }, { it.name }))
        }
    }

    if (rules.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Синхронизировать", style = MaterialTheme.typography.titleMedium)
            Text(
                "Невыбранные режимы глушат только телефон.",
                style = MaterialTheme.typography.bodySmall,
            )

            rules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = SyncFilter.isEnabled(context, rule),
                        onCheckedChange = {
                            SyncFilter.setEnabled(context, rule.id, it)
                            version++
                        },
                    )
                }
            }
        }
    }
}
