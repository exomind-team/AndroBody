package org.exomind.androbody.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.exomind.androbody.nativeapp.AppGraph
import org.exomind.androbody.nativeapp.config.*
import org.exomind.androbody.nativeapp.core.WireFailure
import org.exomind.androbody.nativeapp.diagnostics.*
import org.exomind.androbody.nativeapp.model.endpoint.*
import org.json.JSONArray
import org.json.JSONObject

private data class CatalogProbe(
    val protocol: ModelProtocol,
    val models: List<String>,
    val endpoint: String,
    val traceId: String,
    val selectedModel: String?,
    val query: String = "",
)

/** Connection probes stay attached to the current draft identity and never save it. */
@Composable fun ModelConnectionPanel(graph: AppGraph, protocol: ModelProtocol, draft: ModelDraft,
    drafts: Map<ModelProtocol, ModelDraft>, selectProtocol: (ModelProtocol) -> Unit, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val signature = sha256(listOf(protocol.name, draft.baseUrl, draft.key, draft.model, draft.maxTokens,
        draft.localHttp.toString(), draft.endpointMode.name).joinToString("\u0000").toByteArray())
    val catalogSignature = sha256(listOf(draft.baseUrl, draft.key, draft.localHttp.toString(), draft.endpointMode.name)
        .joinToString("\u0000").toByteArray())
    var job by remember { mutableStateOf<Job?>(null) }
    var catalogJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var catalogResults by remember { mutableStateOf<List<CatalogProbe>>(emptyList()) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogPending by remember { mutableStateOf(false) }
    var catalogDetails by remember { mutableStateOf<ModelProtocol?>(null) }
    var pending by remember { mutableStateOf(false) }
    var confirmProbe by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var generation by remember { mutableLongStateOf(0) }
    var catalogGeneration by remember { mutableLongStateOf(0) }
    val currentSignature by rememberUpdatedState(signature)
    val currentCatalogSignature by rememberUpdatedState(catalogSignature)
    fun applyCatalogModel(targetProtocol: ModelProtocol, modelId: String) {
        val target = drafts.getValue(targetProtocol)
        scope.launch {
            if (!target.loaded) runCatching {
                withContext(Dispatchers.IO) { graph.profiles.read(targetProtocol) }
            }.onSuccess { target.load(it) }
            val sameDestination = target.baseUrl.trim() == draft.baseUrl.trim() &&
                target.localHttp == draft.localHttp && target.endpointMode == draft.endpointMode
            val reusableKey = target.key.takeIf { sameDestination }.orEmpty()
            target.baseUrl = draft.baseUrl
            target.key = draft.key.ifBlank { reusableKey }
            target.localHttp = draft.localHttp
            target.endpointMode = draft.endpointMode
            target.model = modelId
            target.loaded = true
            selectProtocol(targetProtocol)
        }
    }
    val plan = remember(protocol, draft.baseUrl, draft.localHttp, draft.endpointMode) {
        runCatching { EndpointPlanner.plan(EndpointInput(protocol, draft.baseUrl, draft.localHttp, draft.endpointMode)) }
    }
    LaunchedEffect(signature) { generation++; job?.cancel(); pending = false; result = null; confirmProbe = false }
    LaunchedEffect(catalogSignature) {
        catalogGeneration++; catalogJob?.cancel(); catalogPending = false
        catalogResults = emptyList(); catalogError = null
    }
    DisposableEffect(Unit) { onDispose { generation++; catalogGeneration++; job?.cancel(); catalogJob?.cancel() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow("固定完整端点", "关闭时自动识别基础地址，同源尝试合理路径；仅在路径 404/405 时换路，不更换协议、模型或域名。",
            draft.endpointMode == EndpointMode.EXACT, { draft.endpointMode = if (it) EndpointMode.EXACT else EndpointMode.AUTO })
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起候选路径" else "查看候选路径（尚未请求）") }
        if (expanded) plan.fold(onSuccess = { value -> SelectableText(value.candidates.mapIndexed { i, candidate ->
            "${i + 1}. ${candidate.url}\n${candidate.reason}"
        }.joinToString("\n\n"), code = true) }, onFailure = { Text(it.message ?: "地址无效", color = MaterialTheme.colorScheme.error) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("同源目录 GET · 探查两种协议 · 不发提示词", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val inputSignature = catalogSignature
                val inputGeneration = ++catalogGeneration
                val address = draft.baseUrl
                val inputKey = draft.key
                val inputModel = draft.model
                val inputHttp = draft.localHttp
                val inputMode = draft.endpointMode
                catalogResults = emptyList(); catalogError = null; catalogPending = true
                catalogJob = scope.launch {
                    val found = mutableListOf<CatalogProbe>()
                    val failures = mutableListOf<String>()
                    try {
                        for (candidateProtocol in ModelProtocol.entries) {
                            currentCoroutineContext().ensureActive()
                            try {
                                val saved = withContext(Dispatchers.IO) { graph.profiles.read(candidateProtocol) }
                                val route = EndpointPolicy.submission(protocol = candidateProtocol, baseUrl = address,
                                    inputKey = inputKey, model = inputModel.ifBlank { "catalog-probe" }, maxTokens = 64,
                                    allowInsecureLocal = inputHttp, endpointMode = inputMode, saved = saved)
                                val reply = withContext(Dispatchers.IO) {
                                    graph.http.listModels(route, RequestContext(origin = "model-catalog"))
                                }
                                val selectedModel = reply.models.firstOrNull { it == inputModel } ?: reply.models.singleOrNull()
                                found += CatalogProbe(candidateProtocol, reply.models, reply.endpoint, reply.traceId, selectedModel)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                val detail = when (failure) {
                                    is WireFailure -> failure.code + " · " + failure.safeDetail
                                    is IllegalArgumentException -> failure.message ?: "配置需要确认"
                                    else -> "目录请求未完成"
                                }
                                failures += candidateProtocol.label + "：" + detail
                            }
                        }
                        if (currentCatalogSignature == inputSignature && catalogGeneration == inputGeneration) {
                            catalogResults = found
                            catalogError = failures.joinToString("\n").takeIf { it.isNotBlank() }
                        }
                    } catch (cancelled: CancellationException) {
                        if (currentCatalogSignature == inputSignature && catalogGeneration == inputGeneration)
                            catalogError = "探查已取消；未发送模型提示词。"
                        throw cancelled
                    } finally {
                        if (currentCatalogSignature == inputSignature && catalogGeneration == inputGeneration) catalogPending = false
                    }
                }
            }, enabled = enabled && !catalogPending && !pending && draft.baseUrl.isNotBlank() &&
                (draft.key.isNotBlank() || draft.hasStoredKey), modifier = Modifier.testTag("model-catalog-probe")) {
                Text(if (catalogPending) "探查中…" else "探查协议与模型")
            }
        }
        if (catalogPending) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("目录访问可能被服务端记录；不会创建对话或保存配置。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { catalogJob?.cancel() }) { Text("停止") }
        }
        catalogError?.let { SelectableText(it) }
        catalogResults.forEach { probe ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(probe.protocol.label, style = MaterialTheme.typography.titleSmall)
                    Text("协议响应有效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (probe.models.isEmpty()) {
                    Text("端点未公开可列出的模型；可手动填写模型标识后单独测试。", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(value = probe.query, onValueChange = { query ->
                        catalogResults = catalogResults.map { if (it.protocol == probe.protocol) it.copy(query = query) else it }
                    }, label = { Text("筛选模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val matchingModels = probe.models.filter { it.contains(probe.query, ignoreCase = true) }
                    matchingModels.take(40).forEach { modelId ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = probe.selectedModel == modelId, onClick = {
                                catalogResults = catalogResults.map { if (it.protocol == probe.protocol) it.copy(selectedModel = modelId) else it }
                                applyCatalogModel(probe.protocol, modelId)
                            })
                            TextButton(onClick = {
                                catalogResults = catalogResults.map { if (it.protocol == probe.protocol) it.copy(selectedModel = modelId) else it }
                                applyCatalogModel(probe.protocol, modelId)
                            }, modifier = Modifier.weight(1f).testTag("model-option-${probe.protocol.name}-$modelId")) { Text(modelId) }
                        }
                    }
                    if (matchingModels.size > 40)
                        Text("匹配 ${matchingModels.size} 个模型；继续输入可缩小列表。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (matchingModels.isEmpty())
                        Text("没有匹配模型；可修改筛选或手动输入标识。", style = MaterialTheme.typography.bodySmall)
                    Text("当前选择：" + (probe.selectedModel ?: "尚未选择"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("已写入对应协议的本地草稿；保存连接前仍可核对并修改。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { catalogDetails = if (catalogDetails == probe.protocol) null else probe.protocol }) {
                    Text(if (catalogDetails == probe.protocol) "隐藏请求依据" else "查看请求依据")
                }
                if (catalogDetails == probe.protocol)
                    SelectableText("实际 GET 端点：" + probe.endpoint + "\n目录请求：" + probe.traceId, code = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmProbe = true }, enabled = enabled && draft.model.isNotBlank() &&
                !pending && !catalogPending && plan.isSuccess,
                modifier = Modifier.testTag("model-test")) { Text("测试所选模型") }
            if (pending) TextButton(onClick = { job?.cancel() }) { Text("停止测试") }
        }
        if (pending) Text("正在发送固定测试消息；操作状态可在记录页定位。", style = MaterialTheme.typography.bodySmall)
        result?.let { SelectableText(it) }
    }
    if (confirmProbe) AlertDialog(onDismissRequest = { confirmProbe = false }, title = { Text("测试连接") }, text = {
        Text("会向当前草稿目的地发送一条固定、无工具的测试消息，可能产生少量模型费用。不会发送聊天历史，不会保存草稿，也不会改用另一个模型。")
    }, confirmButton = { TextButton(onClick = {
        confirmProbe = false
        val inputSignature = signature
        val inputGeneration = ++generation
        val inputAddress = draft.baseUrl
        val inputKey = draft.key
        val inputModel = draft.model
        val inputHttp = draft.localHttp
        val inputMode = draft.endpointMode
        pending = true
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { graph.profiles.read(protocol) }
                val route = EndpointPolicy.submission(protocol = protocol, baseUrl = inputAddress,
                    inputKey = inputKey, model = inputModel, maxTokens = 128,
                    allowInsecureLocal = inputHttp, endpointMode = inputMode, saved = saved)
                val body = JSONObject().put("model", route.model).put("max_tokens", 128).put("stream", false)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Connection test: reply OK.")))
                val reply = withContext(Dispatchers.IO) { graph.http.requestWithEvidence(route, body,
                    context = RequestContext(origin = "diagnostic")) }
                if (currentSignature == inputSignature && generation == inputGeneration) result = "连接与协议核验通过\n实际端点：${reply.endpoint}\n尝试：${reply.attempts}\n响应模型：${reply.body.optString("model", "上游未报告")}\n诊断：${reply.traceId}\n成功路径已缓存；草稿仍未保存。"
            } catch (cancelled: CancellationException) {
                if (currentSignature == inputSignature && generation == inputGeneration) result = "测试已取消；不会自动重放。实际网络阶段保留在诊断中。"
                throw cancelled
            } catch (failure: Exception) {
                if (currentSignature == inputSignature && generation == inputGeneration) result = when (failure) {
                    is WireFailure -> "${failure.code}\n${failure.safeDetail}"
                    is IllegalArgumentException -> failure.message ?: "测试配置无效；没有覆盖原配置。"
                    else -> "测试配置无效或读取失败；没有覆盖原配置。"
                }
            } finally { if (currentSignature == inputSignature && generation == inputGeneration) pending = false }
        }
    }, modifier = Modifier.testTag("model-test-confirm")) { Text("发送测试") } },
        dismissButton = { TextButton(onClick = { confirmProbe = false }) { Text("取消") } })
}
