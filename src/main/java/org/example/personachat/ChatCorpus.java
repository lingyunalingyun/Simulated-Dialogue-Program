package org.example.personachat;

import javafx.application.Platform;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 把真实聊天记录(JSONL)载入内存，按话题检索相关片段当作语气参考。 */
public class ChatCorpus {

    /** plx=true 表示这条是左边那个人说的，否则是右边的人（按导入时配置的两个 accountName 区分）。 */
    public record Msg(boolean plx, String content) {}

    private final List<Msg> msgs = new ArrayList<>();
    private volatile boolean ready = false;

    public boolean ready() { return ready; }
    public int size() { return msgs.size(); }

    public void loadAsync(Path path, String leftAccount, String rightAccount, Runnable onDone) {
        new Thread(() -> {
            try {
                load(path, leftAccount, rightAccount);
            } catch (Exception ignored) {
            }
            ready = true;
            if (onDone != null) Platform.runLater(onDone);
        }, "corpus").start();
    }

    private void load(Path p, String leftAccount, String rightAccount) throws Exception {
        if (!Files.exists(p)) return;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("\"_type\":\"message\"")) continue;
                JSONObject o = new JSONObject(line);
                if (o.optInt("type", -1) != 0) continue;          // 只要文字
                String content = o.optString("content", "").trim();
                if (content.isEmpty()) continue;
                String acc = o.optString("accountName", "");
                if (acc.equals(leftAccount)) msgs.add(new Msg(true, content));
                else if (acc.equals(rightAccount)) msgs.add(new Msg(false, content));
            }
        }
    }

    /** 按 query 检索最多 k 段真实对话(含上下文)，用 plxName/lyName 标注说话人。 */
    public String retrieve(String query, int k, String plxName, String lyName) {
        if (!ready || msgs.isEmpty() || query == null) return "";
        String q = query.replaceAll("\\s+", "");
        if (q.length() < 2) return "";

        LinkedHashSet<String> gramSet = new LinkedHashSet<>();
        for (int n = 2; n <= 3; n++)
            for (int i = 0; i + n <= q.length(); i++)
                gramSet.add(q.substring(i, i + n));
        List<String> grams = new ArrayList<>(gramSet);
        if (grams.size() > 40) grams = grams.subList(0, 40);

        List<int[]> scored = new ArrayList<>();          // [index, score]
        for (int i = 0; i < msgs.size(); i++) {
            String c = msgs.get(i).content();
            int s = 0;
            for (String g : grams) if (c.contains(g)) s++;
            if (s >= 2) scored.add(new int[]{i, s});
        }
        scored.sort((a, b) -> b[1] - a[1]);

        StringBuilder sb = new StringBuilder();
        Set<Integer> used = new HashSet<>();
        int taken = 0;
        for (int[] sc : scored) {
            if (taken >= k) break;
            int idx = sc[0];
            int from = Math.max(0, idx - 1), to = Math.min(msgs.size() - 1, idx + 2);
            boolean overlap = false;
            for (int j = from; j <= to; j++) if (used.contains(j)) { overlap = true; break; }
            if (overlap) continue;
            for (int j = from; j <= to; j++) {
                used.add(j);
                Msg m = msgs.get(j);
                String txt = m.content();
                if (txt.length() > 50) txt = txt.substring(0, 50) + "…";
                sb.append(m.plx() ? plxName : lyName).append("：").append(txt).append("\n");
            }
            sb.append("\n");
            taken++;
        }
        return sb.toString().trim();
    }
}
