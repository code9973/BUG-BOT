package com.bugbot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BugManagerBot extends TelegramLongPollingBot {

    private static final Logger LOG = Logger.getLogger(BugManagerBot.class.getName());

    private static final String CMD_ACTIVATE = "\u6FC0\u6D3B";
    private static final String CMD_PUBLISH = "\u53D1\u5E03";
    private static final String CMD_ASSIGN = "\u6D3E\u53D1";
    private static final String CMD_TRANSFER = "\u8F6C\u6D3E";
    private static final String CMD_CLAIM = "\u9886\u53D6";
    private static final String CMD_DONE = "\u5B8C\u6210";
    private static final String CMD_UNDO = "\u64A4\u9500";
    private static final String CMD_ARCHIVE = "\u5F52\u6863";
    private static final String CMD_LIST = "\u5217\u8868";
    private static final String CMD_CHECKLIST = "\u6E05\u5355";
    private static final String CMD_VIEW_ATTACHMENT = "\u67E5\u770B\u9644\u4EF6";

    private final DatabaseManager db;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public BugManagerBot() {
        this.db = new DatabaseManager(BotConfig.getDbUrl());
        scheduleDailyReport();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallback(update);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to process update", e);
        }
    }

    private void handleMessage(Update update) {
        if (update.getMessage().hasText()) {
            handleText(update);
            return;
        }

        if (hasSupportedAttachment(update) && update.getMessage().getCaption() != null) {
            handleAttachmentCaption(update);
        }
    }

    private void handleText(Update update) {
        String text = update.getMessage().getText().trim();
        String chatId = update.getMessage().getChatId().toString();
        long userId = update.getMessage().getFrom().getId();
        String userName = safeFirstName(update.getMessage().getFrom().getFirstName());
        String actualUsername = normalizeUsername(update.getMessage().getFrom().getUserName());

        boolean isActivateCmd = textStartsWith(text, CMD_ACTIVATE) || textStartsWith(text, "/activate");
        boolean isHelpCmd = textStartsWith(text, "/help") || textStartsWith(text, "/start");

        if (isActivateCmd) {
            handleActivate(chatId, userId, userName);
            return;
        }

        if (!db.isChatActivated(chatId) && !isHelpCmd) {
            sendMd(chatId, MessageBuilder.errNotActivated());
            return;
        }

        if (textStartsWith(text, "/bug") || textStartsWith(text, CMD_PUBLISH)) {
            handleNewBug(chatId, text);
        } else if (textStartsWith(text, "/assign") || textStartsWith(text, "/dispatch") || textStartsWith(text, CMD_ASSIGN)) {
            handleAssign(chatId, text);
        } else if (textStartsWith(text, "/transfer") || textStartsWith(text, CMD_TRANSFER)) {
            handleTransfer(chatId, text);
        } else if (textStartsWith(text, "/claim") || textStartsWith(text, "/" + CMD_CLAIM) || textStartsWith(text, CMD_CLAIM)) {
            handleClaim(chatId, userId, userName, text);
        } else if (textStartsWith(text, "/done") || textStartsWith(text, "/" + CMD_DONE) || textStartsWith(text, CMD_DONE)) {
            handleDone(chatId, userId, actualUsername, text);
        } else if (textStartsWith(text, "/undo") || textStartsWith(text, "/" + CMD_UNDO) || textStartsWith(text, CMD_UNDO)) {
            handleUndo(chatId, userId, actualUsername, text);
        } else if (textStartsWith(text, "/archive") || textStartsWith(text, CMD_ARCHIVE)) {
            handleArchive(chatId);
        } else if (textStartsWith(text, "/list") || textStartsWith(text, CMD_CHECKLIST) || textStartsWith(text, CMD_LIST)) {
            handleList(chatId);
        } else if (textStartsWith(text, "/attachment") || textStartsWith(text, CMD_VIEW_ATTACHMENT)) {
            handleViewAttachment(chatId, text);
        } else if (isHelpCmd) {
            handleHelp(chatId);
        }
    }

    private void handleAttachmentCaption(Update update) {
        String text = update.getMessage().getCaption().trim();
        String chatId = update.getMessage().getChatId().toString();
        long userId = update.getMessage().getFrom().getId();
        String userName = safeFirstName(update.getMessage().getFrom().getFirstName());

        boolean isActivateCmd = textStartsWith(text, CMD_ACTIVATE) || textStartsWith(text, "/activate");
        if (isActivateCmd) {
            handleActivate(chatId, userId, userName);
            return;
        }

        if (!db.isChatActivated(chatId)) {
            sendMd(chatId, MessageBuilder.errNotActivated());
            return;
        }

        if (textStartsWith(text, "/bug") || textStartsWith(text, CMD_PUBLISH)) {
            AttachmentPayload attachment = extractAttachment(update);
            String desc = stripFirstToken(text);
            if (desc.isEmpty()) {
                sendMd(chatId, MessageBuilder.errPublishWithAttachmentNoCaption());
                return;
            }
            createPendingTask(chatId, desc, attachment);
        }
    }

    private void handleNewBug(String chatId, String text) {
        String desc = stripFirstToken(text);
        if (desc.isEmpty()) {
            sendMd(chatId, MessageBuilder.errNoDesc());
            return;
        }
        createPendingTask(chatId, desc, null);
    }

    private void createPendingTask(String chatId, String description, AttachmentPayload attachment) {
        String id = shortId();
        if (attachment == null) {
            db.saveTask(id, chatId, description);
        } else {
            db.saveTask(id, chatId, description, attachment.type, attachment.fileId, attachment.name);
        }

        Task task = db.getById(id);
        if (task == null) {
            return;
        }

        sendTaskAttachment(chatId, task, "📎 任务附件 #" + task.seq);
        sendWithButton(chatId, MessageBuilder.newTask(task), "🙋 领取", "claim_" + id);
    }

    private void handleAssign(String chatId, String text) {
        String payload = stripFirstToken(text);
        if (payload.isEmpty()) {
            sendMd(chatId, MessageBuilder.errAssignFormat());
            return;
        }

        AssignPayload assignPayload = parseAssignPayload(payload);
        if (assignPayload == null) {
            sendMd(chatId, MessageBuilder.errAssignTargetMissing());
            return;
        }

        if (assignPayload.seq != null) {
            handleAssignExistingTask(chatId, assignPayload);
            return;
        }

        if (assignPayload.description.isEmpty()) {
            sendMd(chatId, MessageBuilder.errAssignFormat());
            return;
        }

        String id = shortId();
        db.saveAssignedTask(id, chatId, assignPayload.description, assignPayload.username, assignPayload.displayName);
        Task task = db.getById(id);
        if (task == null) {
            return;
        }

        sendTaskAttachment(chatId, task, "📎 任务附件 #" + task.seq);
        sendWithButton(chatId, MessageBuilder.assignedTask(task, System.currentTimeMillis()), "✅ 完成", "done_" + id);
    }

    private void handleAssignExistingTask(String chatId, AssignPayload assignPayload) {
        Task existing = db.getBySeq(chatId, assignPayload.seq);
        if (existing == null) {
            sendMd(chatId, MessageBuilder.errNotFound(assignPayload.seq));
            return;
        }
        if (!existing.isPending()) {
            sendMd(chatId, MessageBuilder.errAssignOnlyPending(assignPayload.seq));
            return;
        }

        Task task = db.assignExistingBySeq(chatId, assignPayload.seq, assignPayload.username, assignPayload.displayName);
        if (task == null) {
            sendMd(chatId, MessageBuilder.errAssignOnlyPending(assignPayload.seq));
            return;
        }

        sendTaskAttachment(chatId, task, "📎 任务附件 #" + task.seq);
        sendWithButton(chatId, MessageBuilder.assignedTask(task, System.currentTimeMillis()), "✅ 完成", "done_" + task.id);
    }

    private void handleTransfer(String chatId, String text) {
        String payload = stripFirstToken(text);
        AssignPayload assignPayload = parseAssignPayload(payload);
        if (assignPayload == null || assignPayload.seq == null) {
            sendMd(chatId, MessageBuilder.errTransferFormat());
            return;
        }

        Task existing = db.getBySeq(chatId, assignPayload.seq);
        if (existing == null) {
            sendMd(chatId, MessageBuilder.errNotFound(assignPayload.seq));
            return;
        }
        if (!existing.isInProgress()) {
            sendMd(chatId, MessageBuilder.errTransferOnlyInProgress(assignPayload.seq));
            return;
        }

        Task task = db.reassignInProgressBySeq(chatId, assignPayload.seq, assignPayload.username, assignPayload.displayName);
        if (task == null) {
            sendMd(chatId, MessageBuilder.errTransferOnlyInProgress(assignPayload.seq));
            return;
        }

        sendTaskAttachment(chatId, task, "📎 任务附件 #" + task.seq);
        sendWithButton(chatId, MessageBuilder.reassignedTask(task, System.currentTimeMillis()), "✅ 完成", "done_" + task.id);
    }

    private void handleClaim(String chatId, long userId, String userName, String text) {
        Integer seq = parseSeqArg(text);
        if (seq == null) {
            sendMd(chatId, MessageBuilder.errSeqFormat(CMD_CLAIM));
            return;
        }

        Task existing = db.getBySeq(chatId, seq);
        if (existing == null) {
            sendMd(chatId, MessageBuilder.errNotFound(seq));
            return;
        }
        if (existing.isInProgress() || existing.isDone()) {
            sendMd(chatId, MessageBuilder.errAlreadyClaimed(seq));
            return;
        }

        Task task = db.claimBySeq(chatId, seq, userId, userName);
        if (task == null) {
            sendMd(chatId, MessageBuilder.errAlreadyClaimed(seq));
            return;
        }

        sendTaskAttachment(chatId, task, "📎 任务附件 #" + task.seq);
        sendWithButton(chatId, MessageBuilder.taskClaimed(task, System.currentTimeMillis()), "✅ 完成", "done_" + task.id);
    }

    private void handleDone(String chatId, long userId, String actualUsername, String text) {
        Integer seq = parseSeqArg(text);
        if (seq == null) {
            sendMd(chatId, MessageBuilder.errSeqFormat(CMD_DONE));
            return;
        }

        Task existing = db.getBySeq(chatId, seq);
        if (existing == null) {
            sendMd(chatId, MessageBuilder.errNotFound(seq));
            return;
        }
        if (existing.isPending()) {
            sendMd(chatId, MessageBuilder.errNotClaimed(seq));
            return;
        }
        if (existing.isDone()) {
            sendMd(chatId, MessageBuilder.errAlreadyDone(seq));
            return;
        }
        if (!canOperate(existing, userId, actualUsername)) {
            sendMd(chatId, MessageBuilder.errNotOwner(seq));
            return;
        }

        Task task = db.doneBySeq(chatId, seq, userId, actualUsername);
        if (task == null) {
            sendMd(chatId, MessageBuilder.errAlreadyDone(seq));
            return;
        }

        sendTaskAttachment(chatId, task, "任务附件 #" + task.seq);
        sendMd(chatId, MessageBuilder.taskDone(task));
    }

    private void handleUndo(String chatId, long userId, String actualUsername, String text) {
        Integer seq = parseSeqArg(text);
        if (seq == null) {
            sendMd(chatId, MessageBuilder.errSeqFormat(CMD_UNDO));
            return;
        }

        Task existing = db.getBySeq(chatId, seq);
        if (existing == null) {
            sendMd(chatId, MessageBuilder.errNotFound(seq));
            return;
        }
        if (existing.isPending()) {
            sendMd(chatId, MessageBuilder.errUndoPending(seq));
            return;
        }
        if (!canOperate(existing, userId, actualUsername)) {
            sendMd(chatId, MessageBuilder.errNotOwner(seq));
            return;
        }

        if (existing.isDone()) {
            Task task = db.reopenDoneTask(chatId, seq, userId, actualUsername);
            if (task == null) {
                sendMd(chatId, MessageBuilder.errNotOwner(seq));
                return;
            }
            sendTaskAttachment(chatId, task, "📎 任务附件 #" + task.seq);
            sendWithButton(chatId, MessageBuilder.taskReopened(task), "✅ 完成", "done_" + task.id);
            return;
        }

        Task task = db.revertInProgressTask(chatId, seq, userId, actualUsername);
        if (task == null) {
            sendMd(chatId, MessageBuilder.errNotOwner(seq));
            return;
        }
        sendTaskAttachment(chatId, task, "📎 任务附件 #" + task.seq);
        sendWithButton(chatId, MessageBuilder.taskResetToPending(task), "🙋 领取", "claim_" + task.id);
    }

    private void handleList(String chatId) {
        sendMd(chatId, MessageBuilder.taskList(db.getActiveTasks(chatId), System.currentTimeMillis()));
    }

    private void handleArchive(String chatId) {
        sendMd(chatId, MessageBuilder.archivedTaskList(db.getArchivedTasks(chatId)));
    }

    private void handleViewAttachment(String chatId, String text) {
        Integer seq = parseSeqArg(text);
        if (seq == null) {
            sendMd(chatId, MessageBuilder.errSeqFormat(CMD_VIEW_ATTACHMENT));
            return;
        }

        Task task = db.getBySeq(chatId, seq);
        if (task == null) {
            sendMd(chatId, MessageBuilder.errNotFound(seq));
            return;
        }
        if (!task.hasAttachment()) {
            sendMd(chatId, MessageBuilder.errNoAttachment(seq));
            return;
        }

        sendTaskAttachment(chatId, task, "任务附件 #" + task.seq);
    }

    private void handleActivate(String chatId, long userId, String userName) {
        boolean activated = db.activateChat(chatId, userId, userName);
        sendMd(chatId, activated ? MessageBuilder.activationSuccess() : MessageBuilder.activationAlready());
    }

    private void handleHelp(String chatId) {
        sendMd(chatId, MessageBuilder.helpText(MessageBuilder.minuteOfDayToHHmm(BotConfig.getDailyReportMinuteOfDay())));
    }

    private void handleCallback(Update update) {
        String data = update.getCallbackQuery().getData();
        long userId = update.getCallbackQuery().getFrom().getId();
        String userName = safeFirstName(update.getCallbackQuery().getFrom().getFirstName());
        String actualUsername = normalizeUsername(update.getCallbackQuery().getFrom().getUserName());
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        int msgId = update.getCallbackQuery().getMessage().getMessageId();
        String callbackId = update.getCallbackQuery().getId();

        String chatIdStr = String.valueOf(chatId);
        if (!db.isChatActivated(chatIdStr)) {
            answerAlert(callbackId, MessageBuilder.callbackNotActivated());
            return;
        }

        if (data.startsWith("claim_")) {
            String id = data.substring(6);
            Task task = db.claimByIdInChat(chatIdStr, id, userId, userName);
            if (task == null) {
                answerAlert(callbackId, "⛔ 任务已被领取或已完成。");
                return;
            }

            answerAlert(callbackId, "🙋 已领取任务 #" + task.seq);
            editNoButton(chatId, msgId,
                    "🙋 任务 #" + task.seq + " 已领取\n"
                            + line()
                            + "📝 描述: " + MessageBuilder.esc(task.description) + "\n"
                            + (task.hasAttachment() ? "📎 附件: " + MessageBuilder.esc(task.attachmentName.isEmpty() ? task.attachmentType : task.attachmentName) + "\n" : "")
                            + line()
                            + "👤 负责人: " + MessageBuilder.esc(task.ownerName));

            sendTaskAttachment(chatIdStr, task, "📎 任务附件 #" + task.seq);
            sendWithButton(chatIdStr, MessageBuilder.taskClaimed(task, System.currentTimeMillis()), "✅ 完成", "done_" + task.id);
            return;
        }

        if (data.startsWith("done_")) {
            String id = data.substring(5);
            Task existing = db.getByIdInChat(chatIdStr, id);
            if (existing == null || existing.isDone()) {
                answerAlert(callbackId, "⛔ 任务不存在或已完成。");
                return;
            }
            if (!canOperate(existing, userId, actualUsername)) {
                answerAlert(callbackId, "🚫 只有负责人才能完成任务。");
                return;
            }

            Task task = db.doneByIdInChat(chatIdStr, id, userId, actualUsername);
            if (task == null) {
                answerAlert(callbackId, "⚠️ 操作失败。");
                return;
            }

            answerAlert(callbackId, "✅ 任务 #" + task.seq + " 已完成。");
            editNoButton(chatId, msgId,
                    "✅ 任务 #" + task.seq + " 已完成\n"
                            + line()
                            + "📝 描述: " + MessageBuilder.esc(task.description));

            sendTaskAttachment(chatIdStr, task, "📎 任务附件 #" + task.seq);
            sendMd(chatIdStr, MessageBuilder.taskDone(task));
        }
    }

    private void scheduleDailyReport() {
        int minuteOfDay = BotConfig.getDailyReportMinuteOfDay();
        int intervalHours = BotConfig.getDailyReportIntervalHours();

        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime target = now.toLocalDate()
                .atTime(LocalTime.ofSecondOfDay((long) minuteOfDay * 60))
                .atZone(ZoneId.systemDefault());
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }

        long delayMinutes = Duration.between(now, target).toMinutes();
        LOG.info(String.format("Daily report scheduled at %s, first run in %d minutes",
                MessageBuilder.minuteOfDayToHHmm(minuteOfDay), delayMinutes));

        scheduler.scheduleAtFixedRate(this::sendDailyReport, delayMinutes,
                (long) intervalHours * 60, TimeUnit.MINUTES);
    }

    private void sendDailyReport() {
        try {
            List<String> chatIds = db.getActiveChatIds();
            if (chatIds.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            String timeLabel = MessageBuilder.minuteOfDayToHHmm(BotConfig.getDailyReportMinuteOfDay());
            for (String chatId : chatIds) {
                if (!db.isChatActivated(chatId)) {
                    continue;
                }
                List<Task> tasks = db.getActiveTasks(chatId);
                if (!tasks.isEmpty()) {
                    sendMd(chatId, MessageBuilder.dailyReport(tasks, now, timeLabel));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to send daily report", e);
        }
    }

    private void sendMd(String chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send message chatId=" + chatId, e);
        }
    }

    private void sendWithButton(String chatId, String text, String buttonLabel, String buttonData) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(oneButton(buttonLabel, buttonData))
                    .build());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send button message chatId=" + chatId, e);
        }
    }

    private void sendTaskAttachment(String chatId, Task task, String caption) {
        if (task == null || !task.hasAttachment()) {
            return;
        }

        try {
            if ("photo".equalsIgnoreCase(task.attachmentType)) {
                execute(SendPhoto.builder()
                        .chatId(chatId)
                        .photo(new InputFile(task.attachmentFileId))
                        .caption(caption)
                        .build());
                return;
            }

            if ("video".equalsIgnoreCase(task.attachmentType)) {
                execute(SendVideo.builder()
                        .chatId(chatId)
                        .video(new InputFile(task.attachmentFileId))
                        .caption(caption)
                        .build());
                return;
            }

            execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(task.attachmentFileId))
                    .caption(caption)
                    .build());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to resend attachment for taskId=" + task.id, e);
        }
    }

    private void editNoButton(long chatId, int msgId, String text) {
        try {
            execute(EditMessageText.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(msgId)
                    .text(text)
                    .build());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to edit message msgId=" + msgId, e);
        }
    }

    private void answerAlert(String callbackId, String text) {
        try {
            execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .showAlert(false)
                    .build());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to answer callback", e);
        }
    }

    private InlineKeyboardMarkup oneButton(String label, String data) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(Collections.singletonList(
                        InlineKeyboardButton.builder()
                                .text(label)
                                .callbackData(data)
                                .build()))
                .build();
    }

    private Integer parseSeqArg(String text) {
        String arg = stripFirstToken(text);
        if (arg.isEmpty()) {
            return null;
        }
        try {
            int seq = Integer.parseInt(arg);
            return seq > 0 ? seq : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AssignPayload parseAssignPayload(String payload) {
        String[] parts = payload.trim().split("\\s+", 2);
        if (parts.length == 0 || parts[0].trim().isEmpty()) {
            return null;
        }

        Integer seq = tryParsePositiveInt(parts[0].trim());
        if (seq != null) {
            if (parts.length < 2) {
                return null;
            }
            String[] remainingParts = parts[1].trim().split("\\s+", 2);
            String mention = remainingParts[0].trim();
            if (!mention.startsWith("@") || mention.length() <= 1) {
                return null;
            }
            return new AssignPayload(seq, normalizeUsername(mention), mention, "");
        }

        String mention = parts[0].trim();
        if (!mention.startsWith("@") || mention.length() <= 1) {
            return null;
        }

        String description = parts.length > 1 ? parts[1].trim() : "";
        return new AssignPayload(null, normalizeUsername(mention), mention, description);
    }

    private Integer tryParsePositiveInt(String text) {
        try {
            int value = Integer.parseInt(text);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean canOperate(Task task, long userId, String actualUsername) {
        if (task.ownerId > 0) {
            return task.ownerId == userId;
        }
        return !task.ownerUsername.isEmpty() && task.ownerUsername.equalsIgnoreCase(normalizeUsername(actualUsername));
    }

    private String stripFirstToken(String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ch == ':' || ch == '：') {
                break;
            }
            i++;
        }
        while (i < n) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch) && ch != ':' && ch != '：') {
                break;
            }
            i++;
        }
        return text.substring(i).trim();
    }

    private boolean textStartsWith(String text, String prefix) {
        if (!text.toLowerCase().startsWith(prefix.toLowerCase())) {
            return false;
        }
        if (text.length() == prefix.length()) {
            return true;
        }
        char next = text.charAt(prefix.length());
        return Character.isWhitespace(next) || next == '@' || next == ':' || next == '：';
    }

    private boolean hasSupportedAttachment(Update update) {
        return update.getMessage().hasPhoto() || update.getMessage().hasDocument() || update.getMessage().hasVideo();
    }

    private AttachmentPayload extractAttachment(Update update) {
        if (update.getMessage().hasDocument()) {
            Document document = update.getMessage().getDocument();
            return new AttachmentPayload("document", document.getFileId(), document.getFileName());
        }
        if (update.getMessage().hasVideo()) {
            Video video = update.getMessage().getVideo();
            return new AttachmentPayload("video", video.getFileId(), video.getFileName());
        }
        if (update.getMessage().hasPhoto()) {
            List<PhotoSize> photos = update.getMessage().getPhoto();
            PhotoSize largest = photos.get(photos.size() - 1);
            return new AttachmentPayload("photo", largest.getFileId(), "图片");
        }
        return new AttachmentPayload("", "", "");
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        String normalized = username.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String safeFirstName(String name) {
        return name == null ? "" : name;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String line() {
        return "─────────────────\n";
    }

    @Override
    public String getBotUsername() {
        return BotConfig.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return BotConfig.getBotToken();
    }

    public static void main(String[] args) throws Exception {
        LOG.info("Bug Manager Bot v2.0 starting...");
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(new BugManagerBot());
        LOG.info("Bot registered successfully.");
    }

    private static class AssignPayload {
        private final Integer seq;
        private final String username;
        private final String displayName;
        private final String description;

        private AssignPayload(Integer seq, String username, String displayName, String description) {
            this.seq = seq;
            this.username = username;
            this.displayName = displayName;
            this.description = description;
        }
    }

    private static class AttachmentPayload {
        private final String type;
        private final String fileId;
        private final String name;

        private AttachmentPayload(String type, String fileId, String name) {
            this.type = type == null ? "" : type;
            this.fileId = fileId == null ? "" : fileId;
            this.name = name == null ? "" : name;
        }
    }
}
