package com.tailscale.mclink;

public final class ServerMessages {
    private static final java.util.Map<String, com.google.gson.JsonObject> LANGUAGES = new java.util.HashMap<>();
    private ServerMessages() {}
    public static synchronized String text(String language, String key) {
        com.google.gson.JsonObject messages = LANGUAGES.computeIfAbsent(language, name -> {
            try (java.io.InputStream in = ServerMessages.class.getResourceAsStream("/assets/mclink/lang/" + name + ".json")) {
                if (in != null) return com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception ignored) {}
            return new com.google.gson.JsonObject();
        });
        return messages.has(key) ? messages.get(key).getAsString() : key;
    }
    public static String response(String language, String action, boolean success, Object result) {
        String detail = result instanceof java.nio.file.Path ? result.toString() : "";
        if (!success) return switch (language) {
            case "ko_kr" -> "[Tailcat] " + action + " 실패. 서버 로그와 /tailcat diagnose를 확인해 주세요.";
            case "ja_jp" -> "[Tailcat] " + action + " に失敗しました。サーバーログと /tailcat diagnose を確認してください。";
            default -> "[Tailcat] " + action + " failed. Check the server log and /tailcat diagnose.";
        };
        String text = switch (language) {
            case "ko_kr" -> switch (action) {
                case "start", "restart" -> "공유가 시작되었습니다. /tailcat invite로 코드를 확인하세요.";
                case "stop" -> "공유가 종료되었습니다.";
                case "diagnose" -> "진단 파일 저장: " + detail;
                default -> action;
            };
            case "ja_jp" -> switch (action) {
                case "start", "restart" -> "共有を開始しました。/tailcat invite でコードを確認してください。";
                case "stop" -> "共有を停止しました。";
                case "diagnose" -> "診断ファイルを保存しました: " + detail;
                default -> action;
            };
            default -> switch (action) {
                case "start", "restart" -> "Sharing started. Use /tailcat invite to get the code.";
                case "stop" -> "Sharing stopped.";
                case "diagnose" -> "Diagnostic file saved: " + detail;
                default -> action;
            };
        };
        return "[Tailcat] " + text;
    }
}
