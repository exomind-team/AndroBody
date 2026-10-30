package org.exomind.androbody.nativeapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.gateway.LocalGateway

@Composable fun GatewayScreen(graph: AppGraph, notice: (String) -> Unit) {
    val state by graph.gateway.state.collectAsStateWithLifecycle()
    val prefs by graph.preferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rotate by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val running = state.status == "running"
    val endpoint = "http://127.0.0.1:${LocalGateway.PORT}"
    LazyColumn(contentPadding = PaddingValues(LayoutTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(LayoutTokens.sectionGap)) {
        item {
            SectionTitle("把 Android 交给已有 Agent", "本机请求认证与上游模型认证分开；外部 Harness 保留自己的会话。")
            Spacer(Modifier.height(12.dp))
            InfoPanel(when (state.status) { "running" -> "网关已监听"; "starting" -> "正在启动"; "stopping" -> "正在停止"; "failed" -> "网关失败"; else -> "网关已停止" },
                "${state.detail}\n$endpoint\n当前请求 ${state.activeRequests} · 本次服务已处理 ${state.handledRequests}\n成功请求的上游累计输入 ${state.observedInputTokens} / 输出 ${state.observedOutputTokens} token（非账单）", error = state.status == "failed")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { graph.setGateway(!running) }.onFailure { notice("系统未启动服务；请检查前台状态与通知设置") } },
                    enabled = state.status !in setOf("starting", "stopping")) { Text(if (running) "停止服务" else "启动服务") }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起接入说明" else "接入说明") }
            }
        }
        item {
            SectionTitle("接入凭据", "本机令牌也属于敏感信息。不要把上游 API Key 填入 Termux 的网关认证项。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyToClipboard(context, "AndroBody endpoint", endpoint, false); notice("已复制本机地址") }) { Text("复制地址") }
                OutlinedButton(onClick = { scope.launch {
                    runCatching { withContext(Dispatchers.IO) { graph.credentials.token() } }.onSuccess {
                        copyToClipboard(context, "AndroBody client token", it, true); notice("已复制本机客户端令牌；请勿粘贴到聊天或报告")
                    }.onFailure { notice("凭据存储不可用，未生成替代凭据") }
                } }) { Text("复制令牌") }
            }
            TextButton(onClick = { rotate = true }) { Text("撤销旧令牌并轮换") }
        }
        if (details) item {
            InfoPanel("Termux＋Claude Code", "仅当前 shell 设置 ANTHROPIC_BASE_URL=$endpoint；使用本机令牌作为 ANTHROPIC_AUTH_TOKEN，并移除冲突的 ANTHROPIC_API_KEY。模型名与手机 Anthropic 配置一致。完整安全启动脚本在交接包 source/tools/native-gateway/termux 中。没有实际 Claude Code 验证前，不声明兼容通过。")
            Spacer(Modifier.height(8.dp))
            InfoPanel("协议范围", "支持 /v1/messages、/v1/messages/count_tokens、/v1/chat/completions 和 /v1/models。OpenAI 客户端 base_url 使用 $endpoint/v1。网关 SSE 等待上游完整结果后再返回，不是实时 token 流；原生助手另有独立的实时回复。Responses、任意 URL 转发与 MCP 未实现。混合宿主／Android 工具同轮会明确拒绝。")
        }
        item { SectionTitle("向网关共享的身体能力", "默认全部关闭。所有使用同一客户端令牌的本机调用者共享下列范围，不推断它一定来自某个 App。") }
        items(graph.registry.capabilities.filter { it.gatewayEligible }, key = { it.name }) { capability ->
            ToggleRow(capability.title, capability.name, capability.name in prefs.gatewayScopes, { enabled -> scope.launch {
                runCatching { graph.setGatewayGrant(capability.name, enabled) }.onFailure { notice("能力授权未保存") }
            } })
        }
        item { Text("关闭一项能力会取消当前代理请求；已经发送的模型输入不能撤回。回环地址不等于私有通道，因此每次请求都必须认证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (rotate) AlertDialog(onDismissRequest = { rotate = false }, title = { Text("撤销全部旧连接凭据") },
        text = { Text("网关会停止，当前代理请求取消。新的客户端令牌需要重新复制给 Termux；上游密钥不受影响。") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { graph.rotateGatewayCredential() }.onSuccess { notice("旧令牌已撤销，请重新启动网关并复制新令牌") }.onFailure { notice("轮换未完成，请保持网关停止并检查记录") }
            rotate = false
        } }) { Text("停止并轮换") } }, dismissButton = { TextButton(onClick = { rotate = false }) { Text("取消") } })
}
fun copyToClipboard(context: android.content.Context, label: String, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
