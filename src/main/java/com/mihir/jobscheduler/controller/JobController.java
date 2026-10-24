package com.mihir.jobscheduler.controller;

import com.mihir.jobscheduler.model.Job;
import com.mihir.jobscheduler.service.JobQueueService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobQueueService jobQueueService;

    public JobController(JobQueueService jobQueueService) {
        this.jobQueueService = jobQueueService;
    }

    // Submit a new job
    @PostMapping
    public ResponseEntity<Job> submitJob(
            @RequestBody Map<String, String> request) {

        String type = request.get("type");

        if (type == null || type.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Job job = new Job(type);
        Job savedJob = jobQueueService.enqueueJob(job);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(savedJob);
    }

    // Retrieve an existing job
    @GetMapping("/{id}")
    public ResponseEntity<Job> getJob(@PathVariable String id) {

        Job job = jobQueueService.getJob(id);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(job);
    }
}
