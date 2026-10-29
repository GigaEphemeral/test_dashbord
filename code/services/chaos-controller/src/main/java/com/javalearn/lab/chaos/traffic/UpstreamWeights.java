package com.javalearn.lab.chaos.traffic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal reader of one nginx {@code upstream} block. Only what the split panel
 * needs: server host -> weight (default 1, nginx's own default).
 *
 * <p>An unreadable file or a missing block is {@code available=false} with a
 * reason - never an empty map disguised as "all weights are 1".</p>
 */
public final class UpstreamWeights {

    private static final Pattern SERVER = Pattern.compile(
            "server\\s+([A-Za-z0-9._-]+)(?::\\d+)?(?:[^;]*?\\bweight=([0-9]+))?[^;]*;");

    private UpstreamWeights() {
    }

    public static Result read(Path path, String upstreamName) {
        return read(path, upstreamName, Integer.MAX_VALUE);
    }

    /** Same as {@link #read(Path, String)} with a byte cap (configurable). */
    public static Result read(Path path, String upstreamName, int maxBytes) {
        if (path == null || !Files.isReadable(path)) {
            return Result.unavailable("nginx 配置不可读：" + path);
        }
        try {
            long size = Files.size(path);
            if (size > maxBytes) {
                return Result.unavailable("nginx 配置超过 " + maxBytes + " 字节上限（" + size + "）");
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8), upstreamName);
        } catch (IOException e) {
            return Result.unavailable("读取 nginx 配置失败：" + e.getMessage());
        }
    }

    public static Result parse(String text, String upstreamName) {
        if (text == null || text.isBlank()) {
            return Result.unavailable("nginx 配置为空");
        }
        Pattern block = Pattern.compile(
                "upstream\\s+" + Pattern.quote(upstreamName) + "\\s*\\{([^}]*)}",
                Pattern.DOTALL);
        Matcher matcher = block.matcher(text);
        if (!matcher.find()) {
            return Result.unavailable("未找到 upstream " + upstreamName);
        }
        String body = matcher.group(1);
        Map<String, Integer> weights = new LinkedHashMap<>();
        Matcher server = SERVER.matcher(body);
        while (server.find()) {
            String host = server.group(1).trim();
            int weight = server.group(2) == null ? 1 : Integer.parseInt(server.group(2));
            weights.put(host, weight);
        }
        if (weights.isEmpty()) {
            return Result.unavailable("upstream " + upstreamName + " 没有 server 行");
        }
        return new Result(true, Map.copyOf(weights), null);
    }

    /** @param reason null when available */
    public record Result(boolean available, Map<String, Integer> weights, String reason) {
        public static Result unavailable(String reason) {
            return new Result(false, Map.of(), reason);
        }
    }
}
