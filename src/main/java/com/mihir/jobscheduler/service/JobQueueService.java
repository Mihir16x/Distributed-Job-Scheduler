
package com.mihir.jobscheduler.service;

import com.mihir.jobscheduler.model.Job;
import com.mihir.jobscheduler.model.JobStatus;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class JobQueueService {

    private static final String JOB_PREFIX = "job:";
    private static final String QUEUE_KEY = "jobs:pending";

    private static final String CLAIM_SCRIPT = """
            local jobs = redis.call(
                'ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1],
                'LIMIT', 0, 1
            )

            if #jobs == 0 then
                return nil
            end

            local jobId = jobs[1]

            redis.call('ZREM', KEYS[1], jobId)

            return jobId
            """;

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public JobQueueService(
            StringRedisTemplate redisTemplate,
            JsonMapper jsonMapper) {

        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    // Save a job and add it to the Redis queue.
    public Job enqueueJob(Job job) {
        job.setStatus(JobStatus.PENDING);
        saveJob(job);

        double score = job.getScheduledAt().toEpochMilli();

        redisTemplate.opsForZSet().add(
                QUEUE_KEY,
                job.getId(),
                score
        );

        return job;
    }

    // Retrieve a job by its ID.
    public Job getJob(String jobId) {
        String json = redisTemplate.opsForValue().get(
                JOB_PREFIX + jobId
        );

        if (json == null) {
            return null;
        }

        return jsonMapper.readValue(json, Job.class);
    }

    // Save updated job information in Redis.
    public void saveJob(Job job) {
        String json = jsonMapper.writeValueAsString(job);

        redisTemplate.opsForValue().set(
                JOB_PREFIX + job.getId(),
                json
        );
    }

    // Find jobs whose scheduled execution time has arrived.
    public Set<String> getReadyJobIds() {
        long currentTime = Instant.now().toEpochMilli();

        return redisTemplate.opsForZSet().rangeByScore(
                QUEUE_KEY,
                Double.NEGATIVE_INFINITY,
                currentTime
        );
    }

    // Atomically claim the next ready job using a Redis Lua script.
    public Job claimNextJob() {

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(CLAIM_SCRIPT);
        script.setResultType(String.class);

        String jobId = redisTemplate.execute(
                script,
                List.of(QUEUE_KEY),
                String.valueOf(Instant.now().toEpochMilli())
        );

        if (jobId == null) {
            return null;
        }

        Job job = getJob(jobId);

        if (job != null) {
            job.setStatus(JobStatus.RUNNING);
            saveJob(job);
        }

        return job;
    }

    // Retry failed jobs using exponential backoff.
    public void handleJobFailure(Job job) {

        int nextRetryCount = job.getRetryCount() + 1;

        if (nextRetryCount <= job.getMaxRetries()) {

            job.setRetryCount(nextRetryCount);

            long delaySeconds = 1L << nextRetryCount;

            job.setStatus(JobStatus.RETRYING);
            job.setScheduledAt(
                    Instant.now().plusSeconds(delaySeconds)
            );

            saveJob(job);

            redisTemplate.opsForZSet().add(
                    QUEUE_KEY,
                    job.getId(),
                    job.getScheduledAt().toEpochMilli()
            );

            System.out.println(
                    "Job " + job.getId()
                            + " scheduled for retry " + nextRetryCount
                            + " in " + delaySeconds + " seconds"
            );

        } else {

            job.setStatus(JobStatus.FAILED);
            saveJob(job);

            // Ensure permanently failed jobs are no longer queued.
            redisTemplate.opsForZSet().remove(
                    QUEUE_KEY,
                    job.getId()
            );

            System.out.println(
                    "Job permanently failed: " + job.getId()
            );
        }
    }
}
