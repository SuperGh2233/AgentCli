package com.haocli.llm;

/**
 * 本机 magpie 网关（兼容 OpenAI 的接口，默认 http://127.0.0.1:3425/v1），可以转发 WorkBuddy 等订阅里的模型。
 *
 * <p>magpie 不校验 Key，但工厂要求有 Key 才创建客户端，所以 {@code MAGPIE_API_KEY} 填任意值即可。
 * 不在自动回退列表里：只有显式选 {@code magpie} 时才会用，避免别的服务商出问题时悄悄改走订阅额度。</p>
 */
public class MagpieClient extends AbstractOpenAiCompatibleClient {

    static final String DEFAULT_BASE_URL = "http://127.0.0.1:3425/v1";
    static final String DEFAULT_MODEL = "workbuddy/deepseek-v4.1-flash";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public MagpieClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "magpie";
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
