package org.exomind.androbody.nativeapp.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.exomind.androbody.nativeapp.config.*

@Composable fun ConfigImportDialog(initial: ImportProposal?, initialProtocol: ModelProtocol, dismiss: () -> Unit,
    apply: (ModelProtocol, String?, String?, String?) -> Unit) {
    var raw by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedUrl by remember { mutableStateOf(initial?.endpoints?.singleOrNull()) }
    var selectedKey by remember { mutableStateOf(initial?.keys?.singleOrNull()) }
    var selectedModel by remember { mutableStateOf(initial?.models?.singleOrNull()) }
    var protocol by remember { mutableStateOf(initial?.suggestedProtocol ?: initialProtocol) }
    val context = LocalContext.current
    val input = LocalInputContext.current
    fun inspect(text: String) {
        try {
            val value = ConfigImport.parse(text)
            proposal = value; selectedUrl = value.endpoints.singleOrNull(); selectedKey = value.keys.singleOrNull(); selectedModel = value.models.singleOrNull()
            value.suggestedProtocol?.let { protocol = it }; error = null; raw = ""
        } catch (_: Exception) { error = "文本超过限制或不能解析；未保存、未联网" }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("识别配置，只写入草稿") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支持 URL＋密钥、环境变量与常见 JSON 字段。多候选分别选择，不自动推断配对。")
            OutlinedTextField(value = raw, onValueChange = { if (it.length <= ConfigImport.MAX_INPUT) raw = it else error = "最多 16 Ki 字符" },
                label = { Text("粘贴配置文本") }, maxLines = 6, modifier = Modifier.fillMaxWidth().onFocusChanged { input.editing = it.isFocused },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation())
            ToggleRow("显示导入原文", "原文可能含密钥；仅在当前弹层显示。", visible, { visible = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { inspect(raw) }, enabled = raw.isNotBlank()) { Text("识别文本") }
                TextButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                    if (text == null) error = "剪贴板没有纯文本" else inspect(text)
                }) { Text("从剪贴板识别") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            proposal?.let { value ->
                value.repairs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                SectionTitle("目标协议", "自动猜测仅是建议，可修改。")
                ModelProtocol.entries.forEach { p ->
                    Row { RadioButton(selected = protocol == p, onClick = { protocol = p }); TextButton(onClick = { protocol = p }) { Text(p.label) } }
                }
                CandidateChoices("API 地址", value.endpoints, selectedUrl, { selectedUrl = it }) { it }
                CandidateChoices("密钥", value.keys, selectedKey, { selectedKey = it }, ConfigImport::masked)
                CandidateChoices("模型", value.models, selectedModel, { selectedModel = it }) { it }
                if (value.endpoints.isEmpty() && value.keys.isEmpty() && value.models.isEmpty()) Text("未识别到字段；可关闭后手动配置。")
            }
        }
    }, confirmButton = {
        val value = proposal
        val valid = value != null && (value.endpoints.isEmpty() || selectedUrl != null) && (value.keys.isEmpty() || selectedKey != null) &&
            (value.models.isEmpty() || selectedModel != null) && (selectedUrl != null || selectedKey != null || selectedModel != null)
        TextButton(onClick = { apply(protocol, selectedUrl, selectedKey, selectedModel) }, enabled = valid) { Text("应用到草稿") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable private fun CandidateChoices(title: String, values: List<String>, chosen: String?, choose: (String) -> Unit, label: (String) -> String) {
    if (values.isNotEmpty()) {
        SectionTitle(title, if (values.size > 1) "发现 ${values.size} 个候选，请明确选择" else null)
        values.forEach { value -> Row(Modifier.fillMaxWidth()) {
            RadioButton(selected = value == chosen, onClick = { choose(value) })
            TextButton(onClick = { choose(value) }, modifier = Modifier.weight(1f)) { Text(label(value)) }
        } }
    }
}
