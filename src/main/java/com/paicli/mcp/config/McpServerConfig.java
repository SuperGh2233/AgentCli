package com.paicli.mcp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {
    private String protocolVersion;
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String version) { protocolVersion = version; }
    public com.paicli.mcp.protocol.McpProtocolVersion protocolVersion() {
        return com.paicli.mcp.protocol.McpProtocolVersion.fromConfig(protocolVersion);
    }

    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new LinkedHashMap<>();
    private String url;
    private Map<String, String> headers = new LinkedHashMap<>();
    private boolean disabled;
    private McpOAuthConfig oauth;

    public McpOAuthConfig getOauth() { return oauth; }
    public void setOauth(McpOAuthConfig oauth) { this.oauth = oauth; }
    // 来源只能由 McpConfigLoader 标记；不用 getter/setter 命名，避免配置文件里写同名字段把自己伪装成用户级
    private Path projectConfigFile;
    private String projectFingerprint;
    private String sourceFingerprint;
    private String configFingerprint;
    private byte[] rawSource;
    private List<String> runtimeArgs;
    private Boolean runtimeDisabledOverride;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args == null ? new ArrayList<>() : args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new LinkedHashMap<>() : env;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
        if (rawSource != null) this.runtimeDisabledOverride = disabled;
    }

    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public boolean isHttp() {
        return url != null && !url.isBlank();
    }

    public void markProjectScoped(Path configFile, String fingerprint) {
        this.projectConfigFile = configFile;
        markSourceFingerprint(fingerprint);
        this.projectFingerprint = fingerprint;
    }

    /** User-level configuration is trusted by its owner, but still needs a stable binding fingerprint. */
    public void markUserScoped(String fingerprint) {
        this.projectConfigFile = null;
        this.projectFingerprint = null;
        markSourceFingerprint(fingerprint);
    }

    private void markSourceFingerprint(String fingerprint) {
        captureSource();
        this.sourceFingerprint = fingerprint;
        this.configFingerprint = null;
    }

    private void captureSource() {
        if (rawSource == null) rawSource = McpConfigLoader.fingerprintPayload(this);
    }

    /** Detached copy: the immutable raw snapshot never escapes. */
    public McpServerConfig sourceConfig() {
        captureSource();
        McpServerConfig source = McpConfigLoader.fromPayload(rawSource);
        if (runtimeArgs != null) source.setArgs(new ArrayList<>(runtimeArgs));
        return source;
    }

    public byte[] sourcePayload() {
        captureSource();
        McpServerConfig raw = McpConfigLoader.fromPayload(rawSource);
        if (runtimeArgs == null || runtimeArgs.equals(raw.getArgs())) return rawSource.clone();
        return McpConfigLoader.runtimeSourcePayload(rawSource, runtimeArgs);
    }

    public void restoreSource() {
        McpServerConfig source = sourceConfig();
        protocolVersion = source.protocolVersion;
        command = source.command;
        args = source.args;
        env = source.env;
        url = source.url;
        headers = source.headers;
        disabled = runtimeDisabledOverride == null ? source.disabled : runtimeDisabledOverride;
        oauth = source.oauth;
        configFingerprint = null;
    }

    public void overrideArgs(List<String> args) {
        captureSource();
        runtimeArgs = args == null ? List.of() : List.copyOf(args);
        this.args = new ArrayList<>(runtimeArgs);
        sourceFingerprint = null;
        projectFingerprint = null;
        configFingerprint = null;
    }

    public List<String> runtimeArgs() { return runtimeArgs; }
    public Boolean runtimeDisabledOverride() { return runtimeDisabledOverride; }
    public String sourceFingerprint() { return sourceFingerprint; }
    public void effectiveFingerprint(String fingerprint) { configFingerprint = fingerprint; }

    /** 来自项目目录 .paicli/mcp.json 的 server：随仓库分发，启动前要用户确认。 */
    public boolean projectScoped() {
        return projectConfigFile != null;
    }

    public Path projectConfigFile() {
        return projectConfigFile;
    }

    public String projectFingerprint() {
        return projectFingerprint;
    }

    public String configFingerprint() {
        return configFingerprint;
    }

    public String transportName() {
        return isHttp() ? "http" : "stdio";
    }
}
