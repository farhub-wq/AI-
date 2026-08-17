package com.company.aics.rag;

import com.company.aics.config.AiProperties;
import com.company.aics.domain.DomainModels;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenAI 兼容 Chat Completions 客户端：支持同步生成与 SSE 流式输出。
 * Windows 上同步调用在 OkHttp 失败时可回退到 PowerShell HttpClient，规避部分 TLS/代理环境问题。
 */
@Component
public class OpenAiCompatibleChatClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    /**
     * @param objectMapper JSON 处理
     * @param aiProperties LLM 端点与模型配置
     */
    public OpenAiCompatibleChatClient(
            ObjectMapper objectMapper,
            AiProperties aiProperties
    ) {
        // 强制 HTTP/1.1，降低部分网关对 HTTP/2 流式不兼容的风险
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(90))
                .writeTimeout(Duration.ofSeconds(90))
                .callTimeout(Duration.ofSeconds(120))
                .protocols(List.of(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true)
                .build();
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    /**
     * 同步调用 chat/completions，返回完整助手文本。
     */
    public String generateAnswer(
            String question,
            List<DomainModels.Message> history,
            EvidenceGovernanceService.EvidenceBundle evidenceBundle,
            String intentLabel
    ) {
        validateConfiguration();

        String endpoint = normalizeBaseUrl(aiProperties.getLlmBaseUrl()) + "/chat/completions";
        JsonNode requestBody = buildRequest(question, history, evidenceBundle, intentLabel, false);
        String requestJson;

        try {
            // 转义非 ASCII，降低部分网关对中文 JSON 的解析问题
            requestJson = objectMapper.writer()
                    .with(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature())
                    .writeValueAsString(requestBody);
        } catch (IOException ex) {
            throw new IllegalStateException("序列化 LLM 请求失败。", ex);
        }

        try {
            return extractAnswer(executeWithOkHttp(endpoint, requestJson));
        } catch (IOException ex) {
            if (isWindows()) {
                try {
                    return extractAnswer(executeWithPowerShell(endpoint, requestJson));
                } catch (IOException fallbackEx) {
                    fallbackEx.addSuppressed(ex);
                    throw new IllegalStateException(
                            "LLM 请求失败（OkHttp 与 PowerShell 均失败）: "
                                    + fallbackEx.getClass().getSimpleName() + ": " + fallbackEx.getMessage(),
                            fallbackEx
                    );
                }
            }
            throw new IllegalStateException("LLM 请求失败: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * 流式调用 chat/completions，按 SSE data 行解析 delta 并回调。
     *
     * @param intentLabel 上游意图分类结果，用于强化 Prompt 约束
     */
    public void streamAnswer(
            String question,
            List<DomainModels.Message> history,
            EvidenceGovernanceService.EvidenceBundle evidenceBundle,
            String intentLabel,
            java.util.function.Consumer<String> onDelta
    ) {
        validateConfiguration();

        String endpoint = normalizeBaseUrl(aiProperties.getLlmBaseUrl()) + "/chat/completions";
        JsonNode requestBody = buildRequest(question, history, evidenceBundle, intentLabel, true);
        String requestJson;
        try {
            requestJson = objectMapper.writer()
                    .with(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature())
                    .writeValueAsString(requestBody);
        } catch (IOException ex) {
            throw new IllegalStateException("序列化 LLM 流式请求失败。", ex);
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + aiProperties.getLlmApiKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(requestJson.getBytes(StandardCharsets.UTF_8), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String body = response.body() == null ? "" : response.body().string();
                throw mapUpstreamHttpError(response.code(), body);
            }

            try (var source = response.body().source()) {
                while (!source.exhausted()) {
                    String line = source.readUtf8Line();
                    if (line == null) {
                        break;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        if ("[DONE]".equals(payload)) {
                            break;
                        }
                        continue;
                    }
                    JsonNode chunk = objectMapper.readTree(payload);
                    String delta = extractDeltaText(chunk);
                    if (StringUtils.hasText(delta)) {
                        onDelta.accept(delta);
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("LLM 流式读取失败: " + ex.getMessage(), ex);
        }
    }

    /** 使用 OkHttp 发起同步 POST。 */
    private String executeWithOkHttp(String endpoint, String requestJson) throws IOException {
        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + aiProperties.getLlmApiKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(RequestBody.create(requestJson.getBytes(StandardCharsets.UTF_8), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw mapUpstreamHttpError(response.code(), body);
            }
            return body;
        }
    }

    /**
     * 将上游 HTTP 错误映射为 {@link AiServiceException}，便于降级与全局异常处理。
     */
    private AiServiceException mapUpstreamHttpError(int code, String body) {
        String message = "LLM 请求失败: HTTP " + code + " " + body;
        if (code == 401 || code == 403) {
            return new AiServiceException(AiServiceException.ErrorType.AUTH, message, code, null);
        }
        if (code == 429) {
            return new AiServiceException(AiServiceException.ErrorType.RATE_LIMIT, message, code, null);
        }
        if (code == 404 || (body != null && body.toLowerCase(Locale.ROOT).contains("model"))) {
            return new AiServiceException(AiServiceException.ErrorType.MODEL_UNAVAILABLE, message, code, null);
        }
        return new AiServiceException(AiServiceException.ErrorType.UPSTREAM, message, code, null);
    }

    /**
     * Windows 回退：通过 PowerShell HttpClient 发送请求，避免部分本机 OkHttp TLS 问题。
     */
    private String executeWithPowerShell(String endpoint, String requestJson) throws IOException {
        Path requestFile = Files.createTempFile("aics-llm-request-", ".json");
        Files.writeString(requestFile, requestJson, StandardCharsets.UTF_8);

        String script = """
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                $OutputEncoding = [System.Text.Encoding]::UTF8
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName System.Net.Http
                $body = Get-Content -LiteralPath $env:AICS_LLM_REQUEST_FILE -Raw -Encoding UTF8
                $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
                $client = New-Object System.Net.Http.HttpClient
                $client.Timeout = [TimeSpan]::FromSeconds(120)
                $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $env:AICS_LLM_API_KEY)
                $response = $client.PostAsync($env:AICS_LLM_ENDPOINT, $content).GetAwaiter().GetResult()
                $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if (-not $response.IsSuccessStatusCode) {
                    throw ('HTTP ' + [int]$response.StatusCode + ' ' + $responseBody)
                }
                $responseBody
                """;

        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand", encodedScript);
        Map<String, String> env = builder.environment();
        env.put("AICS_LLM_ENDPOINT", endpoint);
        env.put("AICS_LLM_API_KEY", aiProperties.getLlmApiKey());
        env.put("AICS_LLM_REQUEST_FILE", requestFile.toString());

        try {
            Process process = builder.start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("PowerShell LLM fallback timed out.");
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IOException(StringUtils.hasText(stderr) ? stderr : "PowerShell fallback exited with code " + process.exitValue());
            }
            if (!StringUtils.hasText(stdout)) {
                throw new IOException("PowerShell fallback returned an empty response.");
            }
            return stdout;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("PowerShell LLM fallback interrupted.", ex);
        } finally {
            Files.deleteIfExists(requestFile);
        }
    }

    /** 从响应 JSON 提取助手正文，缺失则抛错。 */
    private String extractAnswer(String responseJson) throws IOException {
        JsonNode responseBody = objectMapper.readTree(responseJson);
        String answer = extractAssistantText(responseBody);
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("LLM response does not contain assistant content.");
        }
        return answer;
    }

    /** @return 当前是否运行在 Windows */
    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    /**
     * 组装 messages：system 提示 + 去重历史 + 含分层知识证据与意图约束的 user 提示。
     */
    private JsonNode buildRequest(
            String question,
            List<DomainModels.Message> history,
            EvidenceGovernanceService.EvidenceBundle evidenceBundle,
            String intentLabel,
            boolean stream
    ) {
        var root = objectMapper.createObjectNode();
        var messages = objectMapper.createArrayNode();
        boolean hasEvidence = evidenceBundle != null && !evidenceBundle.isEmpty();
        String intent = StringUtils.hasText(intentLabel) ? intentLabel : IntentClassifier.CHITCHAT;

        messages.add(objectMapper.createObjectNode()
                .put("role", "system")
                .put("content", buildSystemPrompt(hasEvidence, intent)));

        for (DomainModels.Message message : stripDuplicatedCurrentQuestion(history, question)) {
            if (!StringUtils.hasText(message.content())) {
                continue;
            }
            messages.add(objectMapper.createObjectNode()
                    .put("role", toOpenAiRole(message.role()))
                    .put("content", message.content()));
        }

        messages.add(objectMapper.createObjectNode()
                .put("role", "user")
                .put("content", buildUserPrompt(question, evidenceBundle, intent)));

        root.put("model", aiProperties.getLlmChatModel());
        // 有证据时更低温度，减少编造；投诉/售后再略降一档
        double temperature = !hasEvidence ? 0.4 : 0.15;
        if (hasEvidence && (IntentClassifier.AFTER_SALES.equals(intent) || IntentClassifier.COMPLAINT.equals(intent))) {
            temperature = 0.1;
        }
        root.put("temperature", temperature);
        root.put("stream", stream);
        root.set("messages", messages);
        return root;
    }

    /** 从流式 chunk 的 choices[0].delta.content 提取增量文本。 */
    private String extractDeltaText(JsonNode chunk) {
        JsonNode delta = chunk.path("choices").path(0).path("delta");
        if (delta.hasNonNull("content") && delta.get("content").isTextual()) {
            return delta.get("content").asText();
        }
        return "";
    }

    /**
     * 去掉历史末尾与当前问题重复的用户消息，避免重复注入。
     */
    private List<DomainModels.Message> stripDuplicatedCurrentQuestion(List<DomainModels.Message> history, String question) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<DomainModels.Message> messages = new ArrayList<>(history);
        DomainModels.Message last = messages.getLast();
        if (last.role() == DomainModels.MessageRole.USER && question.equals(last.content())) {
            messages.removeLast();
        }
        return messages;
    }

    /**
     * 按是否有知识证据与意图类型生成系统提示（减幻觉强化版）。
     */
    private String buildSystemPrompt(boolean hasKnowledgeEvidence, String intentLabel) {
        String intentHint = switch (intentLabel) {
            case IntentClassifier.AFTER_SALES -> "当前意图为售后问题：回答必须严格依据政策类证据中的时效、条件与例外，禁止估算天数或运费。";
            case IntentClassifier.COMPLAINT -> "当前意图为投诉：先共情安抚，再给出可执行处理路径；不得承诺证据中不存在的赔偿。";
            case IntentClassifier.CHITCHAT -> "当前意图为闲聊：简短友好回应即可；若问题与购物无关（如天气、时间、百科），如实说明本客服无法提供该类实时/外部信息，不要编造，也不要硬扯成商品推荐；可礼貌邀请用户咨询商品、订单或售后问题。";
            default -> "当前意图为产品咨询：优先回答时效、物流、规格等事实，避免扩展到未提供的售后细则。";
        };

        if (IntentClassifier.CHITCHAT.equals(intentLabel)) {
            return """
                    你是电商平台的企业智能客服助手，请使用简体中文回答。
                    %s
                    本轮为闲聊，未注入知识库证据。
                    必须遵守：不要编造天气、新闻或内部政策数字；回答控制在 2～4 句；不要输出大段营销话术。
                    """.formatted(intentHint);
        }

        if (hasKnowledgeEvidence) {
            return """
                    你是电商平台的企业智能客服助手，请使用简体中文回答。
                    %s
                    必须遵守：
                    1. 只能使用「知识库证据」中编号条目的信息回答，禁止编造政策、价格、时效、运费或订单事实。
                    2. 证据已分层：must_keep_policy 为必须遵守的政策；high_relevance 为高相关事实；background 仅为背景摘要，不得单独推导新规则。
                    3. 若证据不足以完整回答，明确写出“依据不足/不确定”的部分，并追问缺失信息。
                    4. 多条证据冲突时，优先采用 must_keep_policy，再参考 high_relevance，并说明存在差异。
                    5. 不要把不同编号条目的规则混写成一条新规则。
                    6. 回答简洁可执行；不要输出与问题无关的营销话术。
                    """.formatted(intentHint);
        }

        return """
                你是电商平台的企业智能客服助手，请使用简体中文回答。
                %s
                本轮未提供可验证的知识库证据。
                必须遵守：禁止陈述具体内部政策数字或细则；说明当前依据不足，并礼貌追问关键信息。
                """.formatted(intentHint);
    }

    /**
     * 拼装用户提示：意图 + 问题 + 分层编号证据 + 输出约束。
     */
    private String buildUserPrompt(
            String question,
            EvidenceGovernanceService.EvidenceBundle evidenceBundle,
            String intentLabel
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户意图：").append(intentLabel).append("\n");
        builder.append("用户问题：\n");
        builder.append(question.trim());
        builder.append("\n\n");

        if (evidenceBundle == null || evidenceBundle.isEmpty()) {
            if (IntentClassifier.CHITCHAT.equals(intentLabel)) {
                builder.append("知识库证据：本轮闲聊未检索知识库。\n");
                builder.append("请按闲聊意图简短回复，不要编造外部事实，不要强行推荐商品。\n");
            } else {
                builder.append("知识库证据：\n未检索到相关内部证据。\n");
                builder.append("请明确说明依据不足，不要编造细则。\n");
            }
            return builder.toString();
        }

        builder.append("知识库证据（仅可引用下列编号内容；冲突时政策层优先）：\n");
        appendLayer(builder, "【政策层 must_keep_policy — 必须优先遵守】", evidenceBundle.mustKeepPolicy());
        appendLayer(builder, "【高相关层 high_relevance — 直接支撑本题】", evidenceBundle.highRelevance());
        appendLayer(builder, "【背景摘要层 background — 仅作上下文，勿单独立规】", evidenceBundle.background());

        builder.append("\n输出要求：\n");
        builder.append("1. 直接给出可执行回答，关键数字/条件必须能在证据中找到出处。\n");
        builder.append("2. 可在句末用（依据 E1/E2）标注使用的证据编号。\n");
        builder.append("3. 证据未覆盖的部分明确说不知道，禁止猜测。\n");
        return builder.toString();
    }

    private void appendLayer(
            StringBuilder builder,
            String title,
            List<EvidenceGovernanceService.LayeredEvidence> items
    ) {
        builder.append(title).append("\n");
        if (items == null || items.isEmpty()) {
            builder.append("（本层无条目）\n");
            return;
        }
        for (EvidenceGovernanceService.LayeredEvidence item : items) {
            String priority = item.hit().document().priority() == null
                    ? "general"
                    : item.hit().document().priority();
            builder.append("[")
                    .append(item.evidenceId())
                    .append("] 文档=")
                    .append(item.hit().document().fileName())
                    .append("；类型=")
                    .append(priority)
                    .append("；内容=")
                    .append(item.displayText().trim())
                    .append("\n");
        }
    }

    /**
     * 兼容 content 为字符串或数组（多段 text）的助手消息结构。
     */
    private String extractAssistantText(JsonNode responseBody) {
        JsonNode contentNode = responseBody.path("choices").path(0).path("message").path("content");
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : contentNode) {
                if (item.hasNonNull("text")) {
                    builder.append(item.path("text").asText());
                } else if ("text".equals(item.path("type").asText()) && item.hasNonNull("content")) {
                    builder.append(item.path("content").asText());
                }
            }
            return builder.toString();
        }
        return "";
    }

    /** 领域角色映射为 OpenAI role 字符串。 */
    private String toOpenAiRole(DomainModels.MessageRole role) {
        return role == DomainModels.MessageRole.ASSISTANT ? "assistant" : "user";
    }

    /** 去掉基础 URL 末尾斜杠。 */
    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 校验 LLM 基础 URL、API Key、模型名已配置且非占位。 */
    private void validateConfiguration() {
        if (!StringUtils.hasText(aiProperties.getLlmBaseUrl())
                || aiProperties.getLlmBaseUrl().contains("your-llm-provider")) {
            throw new AiServiceException(AiServiceException.ErrorType.CONFIG, "LLM_BASE_URL 未正确配置。");
        }
        if (!StringUtils.hasText(aiProperties.getLlmApiKey()) || "replace-me".equals(aiProperties.getLlmApiKey())) {
            throw new AiServiceException(AiServiceException.ErrorType.CONFIG, "LLM_API_KEY 未正确配置。");
        }
        if (!StringUtils.hasText(aiProperties.getLlmChatModel())) {
            throw new AiServiceException(AiServiceException.ErrorType.CONFIG, "LLM_CHAT_MODEL 未正确配置。");
        }
    }
}
