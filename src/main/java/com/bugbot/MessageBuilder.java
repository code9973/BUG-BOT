package com.bugbot;

import java.util.List;

/**
 * Builds plain-text messages for Telegram.
 */
public class MessageBuilder {

    public static String newTask(Task task) {
        return "🆕 新 Bug 任务 #" + task.seq + "\n"
                + line()
                + "📝 描述: " + safe(task.description) + "\n"
                + attachmentBlock(task)
                + line()
                + "🆔 ID: " + safe(task.id) + "\n"
                + "📌 状态: 待领取\n"
                + "👉 指令: /" + claimCn() + " " + task.seq;
    }

    public static String assignedTask(Task task, long now) {
        return "📨 已派发任务 #" + task.seq + "\n"
                + line()
                + "📝 描述: " + safe(task.description) + "\n"
                + attachmentBlock(task)
                + line()
                + "👤 负责人: " + safe(ownerLabel(task)) + "\n"
                + "⏱ 已耗时: " + formatDuration(now - task.startTime) + "\n"
                + "📌 状态: 处理中\n"
                + "👉 指令: /" + doneCn() + " " + task.seq + "  /" + undoCn() + " " + task.seq;
    }

    public static String reassignedTask(Task task, long now) {
        return "🔄 已转派任务 #" + task.seq + "\n"
                + line()
                + "📝 描述: " + safe(task.description) + "\n"
                + attachmentBlock(task)
                + line()
                + "👤 新负责人: " + safe(ownerLabel(task)) + "\n"
                + "⏱ 累计耗时: " + formatDuration(now - task.startTime) + "\n"
                + "📌 状态: 处理中\n"
                + "👉 指令: /" + doneCn() + " " + task.seq + "  /" + undoCn() + " " + task.seq;
    }

    public static String taskClaimed(Task task, long now) {
        return "🙋 任务处理中 #" + task.seq + "\n"
                + line()
                + "📝 描述: " + safe(task.description) + "\n"
                + attachmentBlock(task)
                + line()
                + "👤 负责人: " + safe(ownerLabel(task)) + "\n"
                + "⏳ 等待时长: " + formatDuration(now - task.startTime) + "\n"
                + "📌 状态: 处理中\n"
                + "👉 指令: /" + doneCn() + " " + task.seq + "  /" + undoCn() + " " + task.seq;
    }

    public static String taskDone(Task task) {
        long fixDuration = task.doneTime - task.claimTime;
        long totalDuration = task.doneTime - task.startTime;
        return "✅ 任务已完成 #" + task.seq + "\n"
                + line()
                + "📝 描述: " + safe(task.description) + "\n"
                + attachmentBlock(task)
                + line()
                + "👤 负责人: " + safe(ownerLabel(task)) + "\n"
                + "🛠 修复时长: " + formatDuration(fixDuration) + "\n"
                + "⏱ 总时长: " + formatDuration(totalDuration) + "\n"
                + "📌 状态: 已完成\n"
                + "👉 指令: /" + undoCn() + " " + task.seq;
    }

    public static String taskReopened(Task task) {
        return "↩️ 已撤销完成 #" + task.seq + "\n"
                + line()
                + "📝 描述: " + safe(task.description) + "\n"
                + attachmentBlock(task)
                + line()
                + "👤 负责人: " + safe(ownerLabel(task)) + "\n"
                + "📌 状态: 回退到处理中\n"
                + "👉 指令: /" + doneCn() + " " + task.seq;
    }

    public static String taskResetToPending(Task task) {
        return "↩️ 已撤销领取/派发 #" + task.seq + "\n"
                + line()
                + "📝 描述: " + safe(task.description) + "\n"
                + attachmentBlock(task)
                + line()
                + "📌 状态: 回退到待领取\n"
                + "👉 指令: /" + claimCn() + " " + task.seq;
    }

    public static String taskList(List<Task> tasks, long now) {
        if (tasks.isEmpty()) {
            return "📭 当前没有未完成任务。";
        }

        StringBuilder sb = new StringBuilder("📋 未完成任务列表（")
                .append(tasks.size()).append("）\n")
                .append(line());
        for (Task task : tasks) {
            sb.append(taskLine(task, now)).append("\n");
        }
        sb.append(line())
                .append("💡 常用指令: /").append(claimCn()).append(" <编号>  /")
                .append(assignCn()).append(" <编号> @username  /")
                .append(transferCn()).append(" <编号> @username  /")
                .append(doneCn()).append(" <编号>  /")
                .append(undoCn()).append(" <编号>");
        return sb.toString();
    }

    public static String archivedTaskList(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return "🗂 当前没有归档任务。";
        }

        StringBuilder sb = new StringBuilder("🗂 归档任务列表（")
                .append(tasks.size()).append("）\n")
                .append(line());
        for (Task task : tasks) {
            sb.append("✅ #").append(task.seq).append(" ")
                    .append(safe(task.description)).append("\n")
                    .append(task.hasAttachment() ? "📎 附件: " + safe(attachmentLabel(task)) + "\n" : "")
                    .append("👤 负责人: ").append(safe(ownerLabel(task)))
                    .append(" | ⏱ 总时长: ").append(formatDuration(task.doneTime - task.startTime))
                    .append("\n");
        }
        sb.append(line())
                .append("💡 可用 /").append(undoCn()).append(" <编号> 重新打开已完成任务。");
        return sb.toString();
    }

    public static String dailyReport(List<Task> tasks, long now, String timeLabel) {
        StringBuilder sb = new StringBuilder("📣 Bug 日报 ")
                .append(safe(timeLabel)).append("\n")
                .append(line());
        for (Task task : tasks) {
            sb.append(taskLine(task, now)).append("\n");
        }
        sb.append(line())
                .append("📌 未完成任务数: ").append(tasks.size());
        return sb.toString();
    }

    public static String helpText(String reportTime) {
        return "🤖 Bug Manager Bot 帮助\n\n"
                + "🚀 首次使用请先发送 " + activateCn() + " 或 /activate\n\n"
                + "🆕 发布任务:\n"
                + publishCn() + " <描述>  或 /bug <描述>\n"
                + "也支持“图片/文件/视频 + 说明文字”一起发布\n\n"
                + "📨 派发任务:\n"
                + assignCn() + " @username <描述>  或 /assign @username <描述>\n"
                + assignCn() + " <编号> @username\n\n"
                + "🔄 转派任务:\n"
                + transferCn() + " <编号> @username  或 /transfer <编号> @username\n\n"
                + "🙋 领取任务:\n"
                + claimCn() + " <编号>  或 /claim <编号>\n\n"
                + "✅ 完成任务:\n"
                + doneCn() + " <编号>  或 /done <编号>\n\n"
                + "↩️ 撤销一步:\n"
                + undoCn() + " <编号>  或 /undo <编号>\n"
                + "已完成 → 处理中，处理中 → 待领取\n\n"
                + "📚 查看列表:\n"
                + listCn() + "  /list\n"
                + archiveCn() + "  /archive\n"
                + viewAttachmentCn() + " <编号>  或 /attachment <编号>\n\n"
                + "⏰ 日报时间: " + safe(reportTime);
    }

    public static String activationSuccess() {
        return "🎉 群组已激活，可用指令: "
                + publishCn() + "、" + assignCn() + "、" + transferCn() + "、"
                + claimCn() + "、" + doneCn() + "、" + undoCn();
    }

    public static String activationAlready() {
        return "ℹ️ 群组已经激活。";
    }

    public static String errNotActivated() {
        return "🔒 群组尚未激活，请先发送 " + activateCn() + " 或 /activate。";
    }

    public static String callbackNotActivated() {
        return "🔒 群组尚未激活。";
    }

    public static String errNoDesc() {
        return "⚠️ 请提供任务描述，例如: " + publishCn() + " 登录接口返回 500";
    }

    public static String errPublishWithAttachmentNoCaption() {
        return "📝 带附件发布任务时，请在附件说明里填写命令和描述，例如: 发布 登录页白屏";
    }

    public static String errAssignFormat() {
        return "⚠️ 派发格式不正确，例如: " + assignCn() + " @alice 修复登录接口  或 " + assignCn() + " 1 @alice";
    }

    public static String errTransferFormat() {
        return "⚠️ 转派格式不正确，例如: " + transferCn() + " 1 @alice";
    }

    public static String errSeqFormat(String cmd) {
        return "⚠️ 格式不正确，例如: " + safe(cmd) + " 3";
    }

    public static String errNotFound(int seq) {
        return "❓ 未找到任务 #" + seq + "。";
    }

    public static String errAlreadyClaimed(int seq) {
        return "⛔ 任务 #" + seq + " 已在处理中或已完成。";
    }

    public static String errAlreadyDone(int seq) {
        return "⛔ 任务 #" + seq + " 已完成。";
    }

    public static String errNotOwner(int seq) {
        return "🚫 你不是任务 #" + seq + " 的负责人。";
    }

    public static String errNotClaimed(int seq) {
        return "📭 任务 #" + seq + " 还未被领取或派发。";
    }

    public static String errUndoPending(int seq) {
        return "ℹ️ 任务 #" + seq + " 当前已经是待领取状态，无需撤销。";
    }

    public static String errAssignTargetMissing() {
        return "👤 派发指令需要带上 @username。";
    }

    public static String errAssignOnlyPending(int seq) {
        return "⛔ 任务 #" + seq + " 不是待领取状态，不能按编号派发。";
    }

    public static String errTransferOnlyInProgress(int seq) {
        return "⛔ 任务 #" + seq + " 不在处理中，不能转派。";
    }

    public static String errNoAttachment(int seq) {
        return "📎 任务 #" + seq + " 没有附件。";
    }

    public static String esc(String text) {
        return safe(text);
    }

    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "0 分钟";
        }
        long totalMinutes = millis / 60000;
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long minutes = totalMinutes % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天 ");
        }
        if (hours > 0) {
            sb.append(hours).append("小时 ");
        }
        sb.append(minutes).append("分钟");
        return sb.toString().trim();
    }

    public static String minuteOfDayToHHmm(int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private static String taskLine(Task task, long now) {
        if (task.isPending()) {
            return "🕓 待领取 #" + task.seq + " " + safe(task.description)
                    + (task.hasAttachment() ? "\n📎 附件: " + safe(attachmentLabel(task)) : "")
                    + "\n⏳ 等待: " + formatDuration(now - task.startTime);
        }

        return "🚧 处理中 #" + task.seq + " " + safe(task.description)
                + (task.hasAttachment() ? "\n📎 附件: " + safe(attachmentLabel(task)) : "")
                + "\n👤 负责人: " + safe(ownerLabel(task))
                + " | ⏱ 已处理: " + formatDuration(now - task.claimTime);
    }

    private static String attachmentBlock(Task task) {
        if (!task.hasAttachment()) {
            return "";
        }
        return "📎 附件: " + safe(attachmentLabel(task)) + "\n";
    }

    private static String attachmentLabel(Task task) {
        if (!task.attachmentName.isEmpty()) {
            return task.attachmentName;
        }
        if (!task.attachmentType.isEmpty()) {
            return task.attachmentType;
        }
        return "已上传附件";
    }

    private static String ownerLabel(Task task) {
        if (task.ownerName != null && !task.ownerName.trim().isEmpty()) {
            return task.ownerName;
        }
        if (task.ownerUsername != null && !task.ownerUsername.trim().isEmpty()) {
            return "@" + task.ownerUsername.trim();
        }
        return "未分配";
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String line() {
        return "─────────────────\n";
    }

    private static String activateCn() {
        return "\u6FC0\u6D3B";
    }

    private static String publishCn() {
        return "\u53D1\u5E03";
    }

    private static String assignCn() {
        return "\u6D3E\u53D1";
    }

    private static String transferCn() {
        return "\u8F6C\u6D3E";
    }

    private static String claimCn() {
        return "\u9886\u53D6";
    }

    private static String doneCn() {
        return "\u5B8C\u6210";
    }

    private static String undoCn() {
        return "\u64A4\u9500";
    }

    private static String archiveCn() {
        return "\u5F52\u6863";
    }

    private static String listCn() {
        return "\u5217\u8868";
    }

    private static String viewAttachmentCn() {
        return "\u67E5\u770B\u9644\u4EF6";
    }
}
