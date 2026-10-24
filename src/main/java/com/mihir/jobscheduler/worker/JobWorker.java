package com.mihir.jobscheduler.worker;

import com.mihir.jobscheduler.model.Job;
import com.mihir.jobscheduler.model.JobStatus;
import com.mihir.jobscheduler.service.JobQueueService;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class JobWorker {

    private final JobQueueService jobQueueService;

    // Three jobs can run concurrently.
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    // Prevent submitting more jobs than available worker slots.
    private final Semaphore availableSlots = new Semaphore(3);

    public JobWorker(JobQueueService jobQueueService) {
        this.jobQueueService = jobQueueService;
    }

    // Check Redis every 200 milliseconds.
    @Scheduled(fixedDelay = 200)
    public void checkForJobs() {

        while (availableSlots.tryAcquire()) {

            Job job;

            try {
                job = jobQueueService.claimNextJob();
            } catch (Exception e) {
                availableSlots.release();
                System.err.println(
                        "Failed to claim job: " + e.getMessage());
                break;
            }

            if (job == null) {
                availableSlots.release();
                break;
            }

            try {
                executor.submit(() -> {
                    try {
                        executeJob(job);
                    } finally {
                        availableSlots.release();
                    }
                });
            } catch (RuntimeException e) {
                availableSlots.release();
                System.err.println(
                        "Failed to submit claimed job: " + job.getId());
                // Advanced crash recovery is outside this MVP.
            }
        }
    }

    private void executeJob(Job job) {

        try {
            System.out.println(
                    Thread.currentThread().getName()
                            + " started job: " + job.getId()
                            + " (attempt " + (job.getRetryCount() + 1) + ")");

            switch (job.getType()) {

                case "email":
                    Thread.sleep(2000);
                    break;

                case "report":
                    Thread.sleep(4000);
                    break;

                case "fail":
                    Thread.sleep(500);
                    throw new RuntimeException(
                            "Simulated job failure");

                default:
                    Thread.sleep(1000);
            }

            job.setStatus(JobStatus.COMPLETED);
            jobQueueService.saveJob(job);

            System.out.println(
                    Thread.currentThread().getName()
                            + " completed job: " + job.getId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println(
                    "Job interrupted: " + job.getId());

        } catch (Exception e) {

            System.err.println(
                    "Job failed: " + job.getId()
                            + " - " + e.getMessage());

            try {
                jobQueueService.handleJobFailure(job);
            } catch (Exception retryError) {
                System.err.println(
                        "Unable to schedule retry for job "
                                + job.getId() + ": "
                                + retryError.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
