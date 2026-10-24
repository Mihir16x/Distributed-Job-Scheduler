package com.mihir.jobscheduler.model;

import java.time.Instant;
import java.util.UUID;

public class Job {

    private String id;
    private String type;
    private JobStatus status;
    private int retryCount;
    private int maxRetries;
    private Instant createdAt;
    private Instant scheduledAt;

    public Job() {
    }

    public Job(String type) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.status = JobStatus.PENDING;
        this.retryCount = 0;
        this.maxRetries = 3;
        this.createdAt = Instant.now();
        this.scheduledAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
}
