/* CallMonitor delivery. GPLv3-or-later with inherited Section 7 terms; see LICENSE. */
package com.baba.callvault.callmonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UploadPanel() {
    val context = LocalContext.current.applicationContext
    val settings = remember { UploadSettings(context) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(settings.enabled()) }
    var configured by remember { mutableStateOf(settings.profile() != null) }
    var blocked by remember { mutableStateOf(settings.authBlocked()) }
    var queueError by remember { mutableStateOf(settings.queueError()) }
    var counts by remember { mutableStateOf<Map<String,Int>>(emptyMap()) }
    var recent by remember { mutableStateOf<List<UploadQueue.Item>>(emptyList()) }
    var showRows by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while(true) {
            withContext(Dispatchers.IO) { val q = UploadQueue.get(context); counts = q.counts(); recent = q.recent() }
            blocked = settings.authBlocked(); configured = settings.profile() != null
            queueError = settings.queueError(); enabled = settings.enabled()
            delay(2000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CallMonitor · отправка записей",style = MaterialTheme.typography.titleMedium)
            Text(if(configured) "${settings.profile()?.device} · build #20" else "Подключите устройство к своему серверу")
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Автоматическая отправка",Modifier.weight(1f))
                Switch(checked = enabled,enabled = configured,onCheckedChange = { value ->
                    enabled = value
                    scope.launch(Dispatchers.IO) { settings.setEnabled(value); UploadScheduler.apply(context) }
                })
            }
            val pending = listOf("pending","retry","uploading").sumOf { counts[it] ?: 0 }
            val errors = counts.filterKeys { it !in setOf("pending","retry","uploading","uploaded") }.values.sum()
            Text("В очереди: $pending · Доставлено: ${counts["uploaded"] ?: 0} · Ошибок: $errors")
            if(blocked) Text("Сервер отклонил доступ. Проверьте токен устройства в настройках.",color=MaterialTheme.colorScheme.error)
            if(queueError) Text("Не удалось добавить запись в очередь. Локальный файл сохранён; требуется проверка.",color=MaterialTheme.colorScheme.error)
            Text("Wi-Fi и мобильный интернет. При отсутствии сети записи ждут в очереди. Локальные файлы после отправки сохраняются.",style=MaterialTheme.typography.bodySmall)
            if(!enabled && configured) Text("Отправка приостановлена. Новые записи продолжают добавляться в очередь.")
            Row {
                Button(onClick={editing=true}) { Text(if(configured) "Настройки" else "Подключить сервер") }
                TextButton(onClick={ showRows = !showRows }) { Text("Очередь") }
            }
            if(showRows) {
                if(recent.isEmpty()) Text("Записей в очереди пока нет.")
                recent.forEach { item ->
                    val date = SimpleDateFormat("dd.MM HH:mm",Locale.ROOT).format(Date(item.createdAt))
                    Text("$date · ${item.id.take(8)} · ${stateLabel(item.state)}\nПопыток: ${item.attempts}" +
                        (item.error?.let { " · $it" } ?: "") + (item.serverId?.let { " · сервер №$it" } ?: ""),
                        style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick={ scope.launch(Dispatchers.IO) { UploadQueue.get(context).resumeLocal(); UploadScheduler.reconcile(context) } }) {
                    Text("Проверить очередь")
                }
            }
        }
    }
    if(editing) {
        val current = remember { settings.profile() }
        var origin by remember { mutableStateOf(current?.origin ?: "https://callmonitor.sensera.online") }
        var tenant by remember { mutableStateOf(current?.tenant ?: "pilot") }
        var device by remember { mutableStateOf(current?.device ?: "redmi-note12-01") }
        var operator by remember { mutableStateOf(current?.operator ?: "operator-01") }
        var token by remember { mutableStateOf("") } // Never saveable, logged or read back to the screen.
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest={if(!saving) editing=false},title={Text("Сервер CallMonitor")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(origin,{origin=it},label={Text("Адрес HTTPS")},singleLine=true)
                OutlinedTextField(tenant,{tenant=it},label={Text("Компания · tenant_id")},singleLine=true)
                OutlinedTextField(device,{device=it},label={Text("Устройство · device_id")},singleLine=true)
                OutlinedTextField(operator,{operator=it},label={Text("Оператор · operator_id")},singleLine=true)
                OutlinedTextField(token,{token=it},label={Text("Токен устройства")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                Text(if(current == null) "Вставьте device_token. После сохранения начнётся отправка новых звонков. Старые записи автоматически не импортируются."
                    else "Для замены вставьте новый device_token. Пустое поле сохраняет текущий токен.",style=MaterialTheme.typography.bodySmall)
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            }
        },confirmButton={TextButton(enabled=!saving,onClick={
            saving=true; error=null
            scope.launch {
                val failure = withContext(Dispatchers.IO) {
                    try {
                        val p = UploadSettings.Profile(UploadContract.origin(origin),UploadContract.identifier(tenant),
                            UploadContract.identifier(device),UploadContract.identifier(operator))
                        val queue = UploadQueue.get(context)
                        require(queue.count() == 0L || p == settings.profile()) { "Для существующей очереди сервер и идентификаторы менять нельзя. Можно заменить токен." }
                        val t = token.takeIf { it.isNotBlank() }?.let { UploadContract.token(it) }
                        require(current != null || t != null) { "Введите токен устройства" }
                        settings.save(p,t,true)
                        if(t != null) queue.resumeAuth()
                        UploadScheduler.apply(context)
                        null
                    } catch(e: IllegalArgumentException) { e.message ?: "Проверьте поля" }
                    catch (_: Exception) { "Не удалось сохранить подключение. Повторите попытку." }
                }
                saving=false
                if(failure == null) { token=""; enabled=true; configured=true; editing=false } else error=failure
            }
        }) { Text(if(saving) "Сохранение…" else "Сохранить") }},dismissButton={TextButton(enabled=!saving,onClick={editing=false}){Text("Отмена")}})
    }
}

private fun stateLabel(state: String): String = when(state) {
    "pending" -> "ожидает отправки"; "retry" -> "повтор при доступной сети"; "uploading" -> "отправляется"
    "uploaded" -> "доставлено"; "auth_error" -> "ошибка доступа"; "conflict" -> "конфликт, нужна проверка"
    "local_error" -> "нет доступа к файлу"; "rejected" -> "запись отклонена"; else -> state
}
