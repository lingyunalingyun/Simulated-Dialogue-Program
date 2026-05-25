package org.example.personachat;

import java.util.List;

/** 一个人设。每次生成时把 SKILL.md + 真实语料片段 + 累积的纠正 组合成 system prompt。 */
public class Persona {
    public final String name;          // 显示名：LX / HMD
    private final String otherName;
    private final String skill;

    /** 给 AI 开场之类的辅助调用读裸人设原文用（不带 prompt 模板）。 */
    public String skillText() { return skill; }

    public Persona(String name, String otherName, String skillText) {
        this.name = name;
        this.otherName = otherName;
        this.skill = skillText;
    }

    public String buildSystem(List<String[]> corrections, String refSnippets) {
        return buildSystem(corrections, refSnippets, null);
    }

    /**
     * @param corrections 该角色累积的纠正，每条 [被纠正的话, 哪里不对]
     * @param refSnippets 本轮检索到的真实聊天片段（可空）
     * @param imageHint   表情包库提示：null/空 = 没图库（不准发图）；"?" = 有图但没标签；"开心, 委屈, ..." = 可选标签
     */
    public String buildSystem(List<String[]> corrections, String refSnippets, String imageHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            你现在就是%s本人，正在用手机和%s私聊。下面三引号内是你的人物设定，严格依据它来说话和思考：
            <<<
            %s
            >>>
            硬性要求：
            - 只输出你这一条要发出去的聊天内容，就像真的在发微信。
            - 不要写自己的名字前缀，不要加引号，不要解释你在扮演谁。
            - **【消息】里绝对禁止任何括号旁白或动作描述**：（叹气）（沉默）（歪头）（笑）（揉了揉眼睛）(sigh) 这类一律不准出现。
              心里的动作/状态请写进【心理】，发出去的【消息】只能是你真要打字发出去的微信内容。
              颜文字本身（比如 (>_<) (≧▽≦) 这种纯符号的）可以保留，区别就是含中文字符描述动作的括号一律禁止。
            - 以短句为主。一条简短消息就一行；想"叮叮叮"连发好几条（情绪上头、补充想法、害羞越说越多、追问）就用换行分隔——**每一行=一个独立的气泡**，会被错开几秒揭示。可自由决定本次只发 1 条还是 2-4 条；自然就好，别为拆而拆。完全贴合你的语气、口头禅和标点习惯。
            - 看到“（旁白：…）”是给你的场景提示，按它调整即可，但不要把旁白本身复述出来。
            - 不管聊到第几轮，你始终是%s本人，绝不跳出角色，绝不提到自己是 AI、模型或在“扮演”。
            - 每次最多两三句，别把同一个字或词无限重复刷屏，别输出乱码或没意义的长串。
            """.formatted(name, otherName, skill, name));

        if (imageHint != null && !imageHint.isBlank()) {
            sb.append("""

                【发表情/图】你有自己的表情包库。想发表情/图时，**单独占一行**写：
                  [图:标签]   或   [图:?]   （? = 随便挑一张）
                这一行会被替换成一张真实的图气泡发出去。和文字消息一样可以混着发：
                  好困哦
                  [图:委屈]
                  晚安
                规则：
                - 一次最多 1-2 张图，别刷屏。心情/情绪/场景需要图就发；纯陈述/正经话别发图。
                - 标签必须从下面"可用标签"里挑；都不贴近就用 [图:?]。
                - [图:xx] 这一行不要带任何其他字。
                """);
            if ("?".equals(imageHint.strip())) {
                sb.append("可用标签：（图库里的图还没人打标签，全用 [图:?]）\n");
            } else {
                sb.append("可用标签：").append(imageHint).append("\n");
            }
        }

        if (refSnippets != null && !refSnippets.isBlank()) {
            sb.append("\n【你俩真实聊过的相关片段，模仿其中的语气、用词和习惯，但不要照抄】\n")
              .append(refSnippets).append("\n");
        }
        if (corrections != null && !corrections.isEmpty()) {
            sb.append("\n【以下是你之前说得不对、必须避免和改正的地方】\n");
            for (String[] c : corrections) {
                sb.append("- 你说过「").append(c[0]).append("」，问题：").append(c[1]).append("\n");
            }
        }
        sb.append("""

            【输出格式】严格按下面五段输出，每段都以对应的方括号标记开头，不要输出任何别的内容、不要解释：
            【隔】这条消息距上一条过了多少分钟，只写整数。你自己判断：秒回就 0-2；过一会儿 5-30；在忙/没看手机/睡着了可以很大（几十到几百分钟）。真实的人不会每条都隔一样久，按当前时间点和情境决定。
            【心理】你此刻真实的内心想法，第一人称、口语、简短（这是你心里想的，不会发出去）
            【消息】你真正要发出去的聊天内容，符合你的语气；可以换行表示连发好几条。**只能是你打字发出去的话本身，不要任何含中文的括号动作旁白（叹气/沉默/歪头/笑/…）**——这类心理动作请写在【心理】里。
            【离开】如果你这条里表示要去做某事/暂时离开（比如去打游戏、去洗澡、去睡觉、去吃饭），就写那件事的简短描述（如“打王者”“睡觉”）；否则这一行留空
            【轮到】接下来你觉得该谁开口：写 %s（对方接话）或 %s（你自己再补一句、连发）。不必一来一回，可以一个人多说几句、另一个少说。
            """.formatted(otherName, name));
        return sb.toString();
    }
}
