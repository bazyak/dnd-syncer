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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bazyak.dndsyncer.core.Access
import com.bazyak.dndsyncer.core.FileLog
import com.bazyak.dndsyncer.core.LogSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bazyak.dndsyncer.core.Shell

private const val TAG_UI = "MainActivity"

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

    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        a11y = Access.isAccessibilityEnabled(context, PhoneSyncService::class.java)
        rules = PhoneNight.dump(context)
        root = Shell.isAvailable()
        // Рут появился или служба слетела: включаемся сами. В фоне — su
        // может ждать подтверждения Magisk.
        if (!a11y) {
            scope.launch {
                val restored = withContext(Dispatchers.IO) { PhoneAccess.restore(context) }
                if (restored) {
                    a11y = Access.isAccessibilityEnabled(context, PhoneSyncService::class.java)
                }
            }
        }
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
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.intro), style = MaterialTheme.typography.bodyMedium)

            PermissionCard(
                title = stringResource(R.string.a11y_title),
                description = stringResource(R.string.a11y_description),
                granted = a11y,
                onGrant = {
                    if (!Access.open(context, Access.accessibilityIntents())) {
                        Toast.makeText(context, R.string.screen_unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            PermissionCard(
                title = stringResource(R.string.root_title),
                description = stringResource(R.string.root_description),
                granted = root,
                onGrant = {
                    root = Shell.isAvailable()
                    if (!root) {
                        Toast.makeText(context, R.string.root_unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            SyncModesCard()

            TokenCard()

            LogCard()

            // Диагностика в самом низу: нужна редко, а места занимает много.
            if (rules.isNotEmpty()) {
                Text(stringResource(R.string.state_title), style = MaterialTheme.typography.titleSmall)
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
                    stringResource(if (granted) R.string.granted else R.string.not_granted),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!granted) Button(onClick = onGrant) { Text(stringResource(R.string.grant)) }
            }
        }
    }
}

@Composable
private fun LogCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(LogSettings.isEnabled(context)) }
    var path by remember { mutableStateOf(PhoneApp.store?.describe().orEmpty()) }
    // Ошибка записи обновляется сама: стоит удалить мешающий файл или
    // сменить папку — следующая строка лога её снимет.
    val problem by remember {
        PhoneApp.store?.problem ?: MutableStateFlow<String?>(null)
    }.collectAsState()

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
            FileLog.d(TAG_UI, "Лог теперь: $path")
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
                Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        LogSettings.setEnabled(context, it)
                    },
                )
            }

            Text(path, style = MaterialTheme.typography.bodySmall)

            val failure = problem
            if (enabled && failure != null) {
                Text(
                    stringResource(R.string.log_failure, failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.log_folder)) }

                if (LogSettings.folder(context) != null) {
                    Button(onClick = {
                        LogSettings.setFolder(context, null)
                        path = PhoneApp.store?.describe().orEmpty()
                        FileLog.d(TAG_UI, "Лог теперь: $path")
                    }) { Text(stringResource(R.string.log_default)) }
                }
            }

            Button(
                onClick = {
                    Thread { PhoneNight.logSnapshot(context) }.start()
                    Toast.makeText(context, R.string.log_snapshot_done, Toast.LENGTH_SHORT).show()
                },
                enabled = enabled,
            ) { Text(stringResource(R.string.log_snapshot)) }
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
            Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sync_hint), style = MaterialTheme.typography.bodySmall)

            rules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (rule.isManual) stringResource(R.string.mode_dnd) else rule.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
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

/**
 * Токен автозапуска Shizuku.
 *
 * Вводится здесь, а не зашивается в сборку: токен меняется при каждой
 * переустановке Shizuku, и пересобирать из-за этого APK бессмысленно.
 * Отсюда он уезжает на часы через Data Layer.
 */
@Composable
private fun TokenCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var saved by remember { mutableStateOf(TokenStore.get(context)) }
    var editing by remember { mutableStateOf(saved.isEmpty()) }
    var draft by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.token_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.token_hint), style = MaterialTheme.typography.bodySmall)

            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("auth") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                TokenStore.set(context, draft)
                                saved = draft.trim()
                                draft = ""
                                editing = false
                                Toast.makeText(
                                    context,
                                    R.string.token_sent,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text(stringResource(R.string.save)) }

                    if (saved.isNotEmpty()) {
                        TextButton(onClick = { draft = ""; editing = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            } else {
                Text(saved, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { draft = saved; editing = true }) { Text(stringResource(R.string.replace)) }
                    TextButton(onClick = {
                        scope.launch {
                            TokenStore.clear(context)
                            saved = ""
                            editing = true
                            Toast.makeText(context, R.string.token_removed, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}
