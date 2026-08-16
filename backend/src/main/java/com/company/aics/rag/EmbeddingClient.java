package com.company.aics.rag;

import com.company.aics.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Embedding 客户端：优先调用 OpenAI 兼容远程接口，未配置或失败时回退本地哈希向量。
 * 本地向量维度固定 384，保证演示环境在无 Key 时仍可完成 RAG 检索。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int LOCAL_DIMENSION = 384;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    /**
     * @param objectMapper JSON 序列化
     * @param aiProperties Embedding 端点与密钥配置
     */
    public EmbeddingClient(ObjectMapper objectMapper, AiProperties aiProperties) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(60))
                .callTimeout(Duration.ofSeconds(75))
                .retryOnConnectionFailure(true)
                .build();
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    /**
     * 对单段文本生成向量；远程失败或未配置时使用本地 embedding。
     */
    public float[] embed(String text) {
        if (!StringUtils.hasText(text)) {
            return localEmbed("");
        }
        if (!isRemoteConfigured()) {
            return localEmbed(text);
        }
        try {
            return remoteEmbed(text);
        } catch (Exception ex) {
            log.warn("Remote embedding failed, falling back to local vectors: {}", ex.getMessage());
            return localEmbed(text);
        }
    }

    /**
     * 批量逐条 embedding（顺序调用）。
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    /** @return 本地向量维度（亦作为 Qdrant collection 创建尺寸参考） */
    public int dimension() {
        return LOCAL_DIMENSION;
    }

    /**
     * 判断远程 Embedding 是否已配置有效 URL/Key/模型（排除占位符）。
     */
    public boolean isRemoteConfigured() {
        return StringUtils.hasText(aiProperties.getEmbeddingBaseUrl())
                && StringUtils.hasText(aiProperties.getEmbeddingApiKey())
                && !"replace-me".equals(aiProperties.getEmbeddingApiKey())
                && StringUtils.hasText(aiProperties.getEmbeddingModel())
                && !aiProperties.getEmbeddingModel().contains("your-embedding");
    }

    /**
     * 调用远程 /embeddings 接口并 L2 归一化返回向量。
     */
    private float[] remoteEmbed(String text) throws IOException {
        String endpoint = normalizeBaseUrl(aiProperties.getEmbeddingBaseUrl()) + "/embeddings";
        Map<String, Object> body = Map.of(
                "model", aiProperties.getEmbeddingModel(),
                "input", text
        );
        String requestJson = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + aiProperties.getEmbeddingApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestJson.getBytes(StandardCharsets.UTF_8), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + responseBody);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                throw new IOException("Embedding response missing vector data.");
            }
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return normalize(vector);
        }
    }

    /**
     * 确定性 n-gram + token 哈希 bag 向量，适配中英文演示检索。
     */
    float[] localEmbed(String text) {
        float[] vector = new float[LOCAL_DIMENSION];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return vector;
        }

        // 字符 unigram / bigram / trigram 哈希累加
        for (int i = 0; i < normalized.length(); i++) {
            int uni = Math.floorMod(normalized.charAt(i) * 131 + 17, LOCAL_DIMENSION);
            vector[uni] += 1.0f;
            if (i + 1 < normalized.length()) {
                int bi = Math.floorMod((normalized.charAt(i) * 31 + normalized.charAt(i + 1)) * 17, LOCAL_DIMENSION);
                vector[bi] += 1.2f;
            }
            if (i + 2 < normalized.length()) {
                int tri = Math.floorMod(
                        (normalized.charAt(i) * 131 + normalized.charAt(i + 1) * 31 + normalized.charAt(i + 2)) * 13,
                        LOCAL_DIMENSION
                );
                vector[tri] += 1.5f;
            }
        }

        // 分词 token 额外加权
        for (String token : normalized.split("[\\s,，。！？、；：/\\\\|_-]+")) {
            if (token.length() < 2) {
                continue;
            }
            int hash = Math.floorMod(token.hashCode(), LOCAL_DIMENSION);
            vector[hash] += 2.0f;
        }
        return normalize(vector);
    }

    /** L2 归一化，便于余弦相似度计算。 */
    private float[] normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum <= 1e-12) {
            return vector;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
        return vector;
    }

    /** 去掉基础 URL 末尾斜杠。 */
    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
