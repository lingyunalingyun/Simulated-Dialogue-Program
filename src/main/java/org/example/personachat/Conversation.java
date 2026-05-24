package org.example.personachat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一份对话记录。每条 turn 有唯一 id（方便单独删除），并能为某一方生成它视角下的 messages。 */
public class Conversation {
    public enum Who { LEFT, RIGHT, NARRATION }

    public record Turn(long id, Who who, String text, String think, String time) {}

    private final List<Turn> turns = new ArrayList<>();
    private long seq = 0;

    public List<Turn> turns() { return turns; }
    public Turn add(Who who, String text) { return add(who, text, "", ""); }
    public Turn add(Who who, String text, String think) { return add(who, text, think, ""); }
    public Turn add(Who who, String text, String think, String time) {
        Turn t = new Turn(++seq, who, text, think == null ? "" : think, time == null ? "" : time);
        turns.add(t);
        return t;
    }
    public void remove(long id) { turns.removeIf(t -> t.id() == id); }
    public void clear() { turns.clear(); }
    public boolean isEmpty() { return turns.isEmpty(); }

    /** 最后一条非旁白消息的内容，用于检索；没有就返回空串。 */
    public String lastSpokenText() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).who() != Who.NARRATION) return turns.get(i).text();
        }
        return "";
    }

    /**
     * 为即将发言的一方构建 messages：自己的话=assistant，对方的话=user，旁白=user 提示。
     * 只取最近 window 条；合并连续同角色；保证 user/assistant 交替且以 user 收尾。
     */
    public JSONArray buildMessages(String systemPrompt, Who sideWho, int window) {
        int start = Math.max(0, turns.size() - window);
        List<String[]> merged = new ArrayList<>();
        for (int i = start; i < turns.size(); i++) {
            Turn t = turns.get(i);
            String role;
            String content = t.text();
            if (t.who() == Who.NARRATION) {
                role = "user";
                content = "（旁白：" + content + "）";
            } else if (t.who() == sideWho) {
                // 自己的话回填成带标记的格式，让模型保持 心理/消息 的输出习惯
                role = "assistant";
                content = (t.think() == null || t.think().isBlank())
                        ? "【消息】" + content
                        : "【心理】" + t.think() + "\n【消息】" + content;
            } else {
                role = "user";   // 对方只看到消息本身，不泄露其心理
            }
            if (!merged.isEmpty() && merged.get(merged.size() - 1)[0].equals(role)) {
                merged.get(merged.size() - 1)[1] += "\n" + content;
            } else {
                merged.add(new String[]{role, content});
            }
        }
        if (merged.isEmpty() || merged.get(merged.size() - 1)[0].equals("assistant")) {
            merged.add(new String[]{"user", "（请自然地发出你接下来想说的话）"});
        }
        if (merged.get(0)[0].equals("assistant")) {
            merged.add(0, new String[]{"user", "（开始吧）"});
        }

        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        for (String[] m : merged) {
            arr.put(new JSONObject().put("role", m[0]).put("content", m[1]));
        }
        return arr;
    }
}
