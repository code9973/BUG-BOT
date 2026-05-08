package com.bugbot;

/**
 * Task entity.
 */
public class Task {

    public final int seq;
    public final String id;
    public final String chatId;
    public final String description;
    public final String attachmentType;
    public final String attachmentFileId;
    public final String attachmentName;
    public final long startTime;
    public final long ownerId;
    public final String ownerName;
    public final String ownerUsername;
    /** 0=pending, 1=in progress, 2=done */
    public final int status;
    public final long claimTime;
    public final long doneTime;

    public Task(int seq, String id, String chatId, String description,
                String attachmentType, String attachmentFileId, String attachmentName,
                long startTime, long ownerId, String ownerName, String ownerUsername,
                int status, long claimTime, long doneTime) {
        this.seq = seq;
        this.id = id;
        this.chatId = chatId;
        this.description = description;
        this.attachmentType = attachmentType == null ? "" : attachmentType;
        this.attachmentFileId = attachmentFileId == null ? "" : attachmentFileId;
        this.attachmentName = attachmentName == null ? "" : attachmentName;
        this.startTime = startTime;
        this.ownerId = ownerId;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.ownerUsername = ownerUsername == null ? "" : ownerUsername;
        this.status = status;
        this.claimTime = claimTime;
        this.doneTime = doneTime;
    }

    public boolean isPending() {
        return status == 0;
    }

    public boolean isInProgress() {
        return status == 1;
    }

    public boolean isDone() {
        return status == 2;
    }

    public boolean hasAttachment() {
        return !attachmentFileId.isEmpty();
    }
}
