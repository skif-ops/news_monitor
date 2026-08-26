package ru.newsmonitor.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(private val app: android.app.Application) :
    androidx.lifecycle.AndroidViewModel(app) {

    private val _config = MutableStateFlow(Storage.loadConfig(app))
    val config = _config.asStateFlow()

    private val _news = MutableStateFlow(Storage.loadNews(app).reversed())
    val news = _news.asStateFlow()

    private val _status = MutableStateFlow("Готов к работе")
    val status = _status.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()

    fun update(change: (Config) -> Unit) {
        val c = _config.value.copy(
            keywords = _config.value.keywords.toMutableList(),
            feeds = _config.value.feeds.toMutableList(),
            vkGroups = _config.value.vkGroups.toMutableList(),
        )
        change(c)
        _config.value = c
        Storage.saveConfig(app, c)
    }

    fun checkNow() {
        if (_checking.value) return
        _checking.value = true
        _status.value = "Идёт проверка..."
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Checker.runCheck(app) }
            _news.value = Storage.loadNews(app).reversed()
            _status.value = result.log.lastOrNull() ?: "Готово"
            _checking.value = false
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        update { it.autoCheck = enabled }
        if (enabled) {
            CheckWorker.schedule(app, _config.value.intervalMinutes)
            _status.value = "Автопроверка включена " +
                "(каждые ${_config.value.intervalMinutes.coerceAtLeast(15)} мин)"
        } else {
            CheckWorker.cancel(app)
            _status.value = "Автопроверка выключена"
        }
    }

    fun exportBytes(): ByteArray {
        val title = "Новости — мониторинг на " +
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        return DocxExporter.build(_news.value, title)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Checker.ensureChannel(this)
        setContent { MaterialTheme { MainScreen() } }
    }
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val config by vm.config.collectAsState()
    val news by vm.news.collectAsState()
    val status by vm.status.collectAsState()
    val checking by vm.checking.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                out.write(vm.exportBytes())
            }
        }
    }

    Scaffold(bottomBar = {
        Column(Modifier.padding(12.dp)) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { vm.checkNow() }, enabled = !checking,
                    modifier = Modifier.weight(1f)) { Text("Проверить сейчас") }
                Text("Авто")
                Switch(checked = config.autoCheck, onCheckedChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.setAutoCheck(enabled)
                })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Новости", "Слова", "RSS", "VK").forEachIndexed { i, name ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        text = { Text(name) })
                }
            }
            when (tab) {
                0 -> NewsTab(news) { exporter.launch("новости_мониторинг.docx") }
                1 -> KeywordsTab(config, vm)
                2 -> FeedsTab(config, vm)
                3 -> VkTab(config, vm)
            }
        }
    }
}

@Composable
fun NewsTab(news: List<NewsItem>, onExport: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Найдено: ${news.size}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onExport, enabled = news.isNotEmpty()) {
                Text("Экспорт в Word")
            }
        }
        if (news.isEmpty()) {
            Text("Пока ничего не найдено.\nНажмите «Проверить сейчас» внизу.",
                Modifier.padding(top = 24.dp))
        }
        LazyColumn {
            items(news) { item ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("${item.source}  |  ${item.date}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Ключевые слова: ${item.keywords}",
                            style = MaterialTheme.typography.bodySmall)
                        if (item.summary.isNotBlank()) {
                            Text(item.summary.take(200),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { uriHandler.openUri(item.link) }) {
                            Text("Открыть источник")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ListEditor(
    title: String,
    hint: String,
    items: List<String>,
    dialogLabel: String,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { input = ""; showDialog = true }) { Text("+ Добавить") }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp))
        HorizontalDivider()
        LazyColumn {
            items(items.indices.toList()) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(items[i], Modifier.weight(1f))
                    TextButton(onClick = { onRemove(i) }) { Text("Удалить") }
                }
                HorizontalDivider()
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(dialogLabel) },
            text = {
                OutlinedTextField(value = input, onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (input.isNotBlank()) onAdd(input.trim())
                    showDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun KeywordsTab(config: Config, vm: MainViewModel) {
    ListEditor(
        title = "Ключевые слова",
        hint = "Подсказка: «санкци» найдёт и «санкции», и «санкционный». Регистр не важен.",
        items = config.keywords,
        dialogLabel = "Новое ключевое слово или фраза",
        onAdd = { kw -> vm.update { it.keywords.add(kw) } },
        onRemove = { i -> vm.update { it.keywords.removeAt(i) } },
    )
}

@Composable
fun FeedsTab(config: Config, vm: MainViewModel) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Источники RSS", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { name = ""; url = ""; showDialog = true }) { Text("+ Добавить") }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LazyColumn {
            items(config.feeds.indices.toList()) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(config.feeds[i].name)
                        Text(config.feeds[i].url,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    TextButton(onClick = { vm.update { it.feeds.removeAt(i) } }) {
                        Text("Удалить")
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Новый источник RSS") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("Адрес RSS-ленты (https://…)") },
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.trim().startsWith("http")) {
                        vm.update { it.feeds.add(Feed(name.trim(), url.trim())) }
                    }
                    showDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun VkTab(config: Config, vm: MainViewModel) {
    var token by remember(config.vkToken) { mutableStateOf(config.vkToken) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Ключ доступа VK (с dev.vk.com)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.update { it.vkToken = token.trim() } }) {
                    Text("Сохранить ключ")
                }
            }
        }
        ListEditor(
            title = "Сообщества VK",
            hint = "Сообщество можно указать любым способом: tass_agency или https://vk.com/tass_agency",
            items = config.vkGroups,
            dialogLabel = "Новое сообщество VK",
            onAdd = { g -> vm.update { it.vkGroups.add(VkSource.normalizeGroup(g)) } },
            onRemove = { i -> vm.update { it.vkGroups.removeAt(i) } },
        )
    }
}
