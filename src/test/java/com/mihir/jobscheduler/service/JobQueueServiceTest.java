
package com.mihir.jobscheduler.service;

import com.mihir.jobscheduler.model.Job;
import com.mihir.jobscheduler.model.JobStatus;
import com.mihir.jobscheduler.worker.JobWorker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class JobQueueServiceTest {

    @Autowired
    private JobQueueService jobQueueService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Prevent background workers from consuming test jobs.
    @MockitoBean
    private JobWorker jobWorker;

    private final List<String> testJobIds = new ArrayList<>();

    private Job createTestJob() {
        Job job = new Job("test");
        testJobIds.add(job.getId());
        return job;
    }

    @AfterEach
    void cleanup() {
        for (String id : testJobIds) {
            redisTemplate.opsForZSet().remove(
                    "jobs:pending", id);
            redisTemplate.delete("job:" + id);
        }

        testJobIds.clear();
    }

    @Test
    void jobIsStoredInRedis() {
        Job job = createTestJob();

        jobQueueService.enqueueJob(job);

        Job stored = jobQueueService.getJob(job.getId());

        assertNotNull(stored);
        assertEquals(job.getId(), stored.getId());
        assertEquals(JobStatus.PENDING, stored.getStatus());
        assertEquals(0, stored.getRetryCount());
    }

    @Test
    void jobCanBeClaimed() {
        Job job = createTestJob();

        jobQueueService.enqueueJob(job);

        Job claimed = jobQueueService.claimNextJob();

        assertNotNull(claimed);
        assertEquals(job.getId(), claimed.getId());
        assertEquals(JobStatus.RUNNING, claimed.getStatus());

        Job stored = jobQueueService.getJob(job.getId());

        assertEquals(JobStatus.RUNNING, stored.getStatus());
    }

    @Test
    void concurrentClaimsDoNotDuplicateJobs() throws Exception {
        Job job = createTestJob();
        jobQueueService.enqueueJob(job);

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Job>> futures = new ArrayList<>();

            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return jobQueueService.claimNextJob();
                }));
            }

            start.countDown();

            int successfulClaims = 0;

            for (Future<Job> future : futures) {
                Job result = future.get(10, TimeUnit.SECONDS);

                if (result != null
                        && result.getId().equals(job.getId())) {
                    successfulClaims++;
                }
            }

            assertEquals(1, successfulClaims);

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedJobIsScheduledForRetry() {
        Job job = createTestJob();

        jobQueueService.enqueueJob(job);
        jobQueueService.handleJobFailure(job);

        assertEquals(1, job.getRetryCount());
        assertEquals(JobStatus.RETRYING, job.getStatus());

        long delay = job.getScheduledAt().getEpochSecond()
                - Instant.now().getEpochSecond();

        assertTrue(delay >= 1 && delay <= 3);

        Double score = redisTemplate.opsForZSet()
                .score("jobs:pending", job.getId());

        assertNotNull(score);
        assertEquals(
                job.getScheduledAt().toEpochMilli(),
                score.longValue());
    }

    @Test
    void retryDelayIncreasesExponentially() {
        Job job = createTestJob();

        jobQueueService.handleJobFailure(job);

        long firstDelay = job.getScheduledAt().getEpochSecond()
                - Instant.now().getEpochSecond();

        jobQueueService.handleJobFailure(job);

        long secondDelay = job.getScheduledAt().getEpochSecond()
                - Instant.now().getEpochSecond();

        jobQueueService.handleJobFailure(job);

        long thirdDelay = job.getScheduledAt().getEpochSecond()
                - Instant.now().getEpochSecond();

        assertTrue(firstDelay >= 1 && firstDelay <= 3);
        assertTrue(secondDelay >= 3 && secondDelay <= 5);
        assertTrue(thirdDelay >= 7 && thirdDelay <= 9);

        assertEquals(3, job.getRetryCount());
    }

    @Test
    void jobFailsAfterMaximumRetries() {
        Job job = createTestJob();

        jobQueueService.enqueueJob(job);

        // Three retries are allowed after the initial attempt.
        jobQueueService.handleJobFailure(job);
        jobQueueService.handleJobFailure(job);
        jobQueueService.handleJobFailure(job);

        assertEquals(3, job.getRetryCount());
        assertEquals(JobStatus.RETRYING, job.getStatus());

        // Failure on the fourth attempt ends the job.
        jobQueueService.handleJobFailure(job);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(3, job.getRetryCount());

        Job stored = jobQueueService.getJob(job.getId());

        assertNotNull(stored);
        assertEquals(JobStatus.FAILED, stored.getStatus());

        Double queueScore = redisTemplate.opsForZSet()
                .score("jobs:pending", job.getId());

        assertNull(queueScore);

    }
}
