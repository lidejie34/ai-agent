package com.dj.ai.agentchat.orchestration.planner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Planner 结构化 JSON 协议解析（迭代5，T2，纯函数可离线单测）。
 *
 * <p>容错四道防线的前两道在此：① ```json 代码块围栏提取；② 散文包裹时的首个平衡
 * {@code {...}} 段（括号配平，跳过字符串/转义）。随后 fastjson2 解析 + 字段校验；
 * 任何异常/字段缺失/类型错 → {@code Unparseable}（不抛出），由调用方重试 1 次后
 * 降级（路由）或强制收尾（再规划）。
 */
public final class PlannerProtocol {

    /** taskId 宽松校验：1~32 位字母数字/下划线/连字符。 */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    /** ```json ... ``` 或 ``` ... ``` 围栏（DOTALL，取围栏体内 JSON）。 */
    private static final Pattern FENCE_PATTERN =
            Pattern.compile("```(?:[a-zA-Z0-9]*)?\\s*(.*?)```", Pattern.DOTALL);

    private PlannerProtocol() {
    }

    /**
     * 解析路由轮输出。
     *
     * @param maxTasks 计划任务数硬顶；超出保留前 N 个并置 truncated=true（AC-13）
     */
    public static RouteDecision parseRoute(String raw, int maxTasks) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                return new RouteDecision.Unparseable();
            }
            JSONObject obj = JSON.parseObject(json);
            if (obj == null) {
                return new RouteDecision.Unparseable();
            }
            String mode = obj.getString("mode");
            if ("direct".equals(mode)) {
                String answer = obj.getString("answer");
                if (!StringUtils.hasText(answer)) {
                    return new RouteDecision.Unparseable();
                }
                return new RouteDecision.Direct(answer);
            }
            if ("plan".equals(mode)) {
                JSONArray tasksArr = obj.getJSONArray("tasks");
                if (tasksArr == null || tasksArr.isEmpty()) {
                    // mode=plan 但 tasks 空/缺失 = 非法计划（AC-14）
                    return new RouteDecision.Unparseable();
                }
                int limit = Math.max(1, maxTasks);
                int total = tasksArr.size();
                boolean truncated = total > limit;
                int count = Math.min(total, limit);
                List<TaskSpec> tasks = new ArrayList<>(count);
                Set<String> usedIds = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    JSONObject t = tasksArr.getJSONObject(i);
                    String title = t == null ? null : t.getString("title");
                    if (!StringUtils.hasText(title)) {
                        return new RouteDecision.Unparseable();
                    }
                    String goal = t.getString("goal");
                    String id = normalizeTaskId(t.getString("taskId"), i + 1, usedIds);
                    tasks.add(new TaskSpec(id, title.trim(), goal == null ? "" : goal));
                }
                return new RouteDecision.Plan(List.copyOf(tasks), truncated);
            }
            return new RouteDecision.Unparseable();
        } catch (RuntimeException e) {
            // 解析/字段缺失/类型错一律 Unparseable，不抛出
            return new RouteDecision.Unparseable();
        }
    }

    /**
     * 解析再规划轮输出：action=final → Final；action=next 且 task.title 非空白 → Next
     * （skipped 可选，非字符串元素忽略）；其余 → Unparseable。
     */
    public static ReplanDecision parseReplan(String raw) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                return new ReplanDecision.Unparseable();
            }
            JSONObject obj = JSON.parseObject(json);
            if (obj == null) {
                return new ReplanDecision.Unparseable();
            }
            String action = obj.getString("action");
            if ("final".equals(action)) {
                return new ReplanDecision.Final();
            }
            if ("next".equals(action)) {
                JSONObject taskObj = obj.getJSONObject("task");
                String title = taskObj == null ? null : taskObj.getString("title");
                if (!StringUtils.hasText(title)) {
                    return new ReplanDecision.Unparseable();
                }
                String goal = taskObj.getString("goal");
                // 新任务 taskId 的归一化（补 tN/去重）由编排层台账按全局序号完成
                TaskSpec task = new TaskSpec(taskObj.getString("taskId"), title.trim(),
                        goal == null ? "" : goal);
                List<String> skipped = new ArrayList<>();
                JSONArray skippedArr = obj.getJSONArray("skipped");
                if (skippedArr != null) {
                    for (int i = 0; i < skippedArr.size(); i++) {
                        String sid = skippedArr.getString(i);
                        if (StringUtils.hasText(sid)) {
                            skipped.add(sid.trim());
                        }
                    }
                }
                return new ReplanDecision.Next(task, List.copyOf(skipped));
            }
            return new ReplanDecision.Unparseable();
        } catch (RuntimeException e) {
            return new ReplanDecision.Unparseable();
        }
    }

    /**
     * 从模型输出中提取 JSON 对象文本：① 代码块围栏优先；② 围栏内/散文中的首个平衡
     * {@code {...}} 段（括号配平，跳过字符串与转义）。提取不到返回 null。
     *
     * <p>公开供 ExecutorClient 复用（Executor 的 ok/error 契约同样可能被模型包进围栏/散文）。
     */
    public static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher fence = FENCE_PATTERN.matcher(raw);
        if (fence.find()) {
            String body = fence.group(1);
            String inFence = balancedObject(body);
            if (inFence != null) {
                return inFence;
            }
        }
        return balancedObject(raw);
    }

    /** 取文本中首个平衡 {@code {...}} 子串；无则 null。 */
    private static String balancedObject(String text) {
        int start = text.indexOf('{');
        int end = balancedEnd(text, start);
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    /** 从 openIndex（'{'）起括号配平扫描，跳过 JSON 字符串与转义；返回匹配 '}' 下标，无则 -1。 */
    private static int balancedEnd(String s, int openIndex) {
        if (openIndex < 0) {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * taskId 归一化：空白/非法/重复 → 按序号补 {@code tN}；兜底冲突时追加下划线。
     * 公开供编排层对再规划新任务做全局台账内去重（T4）。
     */
    public static String normalizeTaskId(String raw, int index, Set<String> used) {
        String candidate = (raw != null && TASK_ID_PATTERN.matcher(raw).matches() && !used.contains(raw))
                ? raw : "t" + index;
        while (used.contains(candidate)) {
            candidate = candidate + "_";
        }
        used.add(candidate);
        return candidate;
    }
}
