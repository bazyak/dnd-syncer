package com.bazyak.dndsyncer.wear

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bazyak.dndsyncer.core.Access
import com.bazyak.dndsyncer.core.Shell
import com.bazyak.dndsyncer.core.ShizukuShell
import com.bazyak.dndsyncer.core.Zen

class MainActivity : ComponentActivity() {

    private val shizukuListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener) }

        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ShizukuKeepAliveReceiver.schedule(this)

        setContent { MaterialTheme { WelcomeScreen() } }
    }

    override fun onDestroy() {
        runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onDestroy()
    }

    companion object {
        const val SHIZUKU_REQUEST = 4242
    }
}

@Composable
private fun WelcomeScreen() {
    val context = LocalContext.current
    var a11y by remember {
        mutableStateOf(Access.isAccessibilityEnabled(context, WatchSyncService::class.java))
    }
    var secure by remember { mutableStateOf(Zen.canWriteSecure(context)) }
    var shell by remember { mutableStateOf(Shell.isAvailable()) }
    var shizukuAsks by remember { mutableStateOf(ShizukuShell.needsPermission()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        a11y = Access.isAccessibilityEnabled(context, WatchSyncService::class.java)
        secure = Zen.canWriteSecure(context)
        shell = Shell.isAvailable()
        shizukuAsks = ShizukuShell.needsPermission()
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("DND syncer") } }
            item {
                Centered(
                    "Синхронизирует режимы с телефоном. Театр и ночь уезжают " +
                        "на телефон, оттуда возвращаются обратно.",
                    MaterialTheme.typography.bodySmall,
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Специальные возможности", style = MaterialTheme.typography.titleSmall)
                    Centered(
                        if (a11y) "Выдано" else "Держит приложение активным",
                        MaterialTheme.typography.bodyExtraSmall,
                    )
                    if (!a11y) {
                        Button(
                            onClick = {
                                if (!Access.open(context, Access.accessibilityIntents())) {
                                    Toast.makeText(
                                        context,
                                        "Экран недоступен",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Разрешить") }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Shizuku", style = MaterialTheme.typography.titleSmall)
                    Centered(
                        when {
                            shell -> "Выдано"
                            shizukuAsks -> "Нужно разрешение"
                            else -> "Сервис не запущен"
                        },
                        MaterialTheme.typography.bodyExtraSmall,
                    )
                    if (shizukuAsks) {
                        Button(
                            onClick = {
                                ShizukuShell.requestPermission(MainActivity.SHIZUKU_REQUEST)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Разрешить") }
                    } else if (!shell) {
                        Button(
                            onClick = {
                                Toast.makeText(context, "Поднимаю…", Toast.LENGTH_SHORT).show()
                                Thread {
                                    val result = ShizukuStarter.ensureRunning(context)
                                    Log.d("MainActivity", "Ручной запуск: $result")
                                }.start()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Запустить") }
                    }
                }
            }

            item { AdbItem("Запись системных настроек", secure) }

            item {
                Centered(
                    if (a11y && shell && secure) {
                        "Всё готово"
                    } else {
                        "Недостающее выдаётся с компьютера через adb, " +
                            "команды в README"
                    },
                    MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun AdbItem(title: String, granted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Centered(
            if (granted) "Выдано" else "Только через adb",
            MaterialTheme.typography.bodyExtraSmall,
        )
    }
}

@Composable
private fun Centered(text: String, style: androidx.compose.ui.text.TextStyle) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = style,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
