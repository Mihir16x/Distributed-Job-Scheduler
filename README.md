# Distributed Job Scheduler

A lightweight distributed job processing service built with Java, Spring Boot, and Redis. The application provides REST APIs for submitting and tracking background jobs, processes jobs concurrently using worker threads, and automatically retries failed jobs with exponential backoff.

## Features

- **REST API:** Submit jobs and retrieve their current status.
- **Redis-backed queue:** Store job metadata and schedule execution using a Redis sorted set.
- **Concurrent workers:** Process up to three jobs simultaneously using a fixed thread pool.
- **Atomic job claiming:** Use a Redis Lua script to ensure concurrent workers cannot claim the same queued job.
- **Automatic retries:** Reschedule failed jobs using exponential backoff.
- **Failure handling:** Mark jobs as permanently failed when their retry limit is exhausted.
- **Automated testing:** JUnit tests covering job storage, atomic claiming, concurrency, retry scheduling, and failure handling.

## Tech Stack

- Java 21
- Spring Boot 4
- Spring Web MVC
- Spring Data Redis
- Redis 7
- Maven
- Docker
- JUnit 5

## Architecture

```text
                  Client
                    |
                    v
             Spring Boot API
                    |
          POST /jobs, GET /jobs/{id}
                    |
                    v
              JobQueueService
                    |
                    v
            Redis Sorted Set
              jobs:pending
                    |
                    v
           Atomic Lua Job Claim
                    |
           +--------+--------+
           |        |        |
           v        v        v
        Worker 1 Worker 2 Worker 3
           |        |        |
           +--------+--------+
                    |
             Job Execution
                    |
           +--------+--------+
           |                 |
         Success           Failure
           |                 |
           v                 v
       COMPLETED        Retry with
                      exponential backoff
                            |
                    Retry limit reached?
                            |
                            v
                          FAILED
```

## Job Lifecycle

Jobs move through the following statuses:

| Status    | Description                                         |
| --------- | --------------------------------------------------- |
| PENDING   | Job has been submitted and is waiting for execution |
| RUNNING   | A worker has claimed the job                        |
| RETRYING  | Job failed and is scheduled for another attempt     |
| COMPLETED | Job executed successfully                           |
| FAILED    | Job exhausted its allowed retries                   |

Each job has a unique UUID, job type, retry count, maximum retries, creation timestamp, and scheduled execution timestamp.

## REST API

### Submit a Job

**Endpoint:** `POST /jobs`

Example request:

```json
{
  "type": "email"
}
```

Example response:

```json
{
  "id": "bdb81514-9753-4e53-b946-abf25f986f4c",
  "type": "email",
  "status": "PENDING",
  "retryCount": 0,
  "maxRetries": 3,
  "createdAt": "2026-10-24T20:00:00Z",
  "scheduledAt": "2026-10-24T20:00:00Z"
}
```

Returns HTTP `201 Created`.

### Retrieve Job Status

**Endpoint:** `GET /jobs/{id}`

Example:

```text
GET http://localhost:8080/jobs/bdb81514-9753-4e53-b946-abf25f986f4c
```

Example response after successful processing:

```json
{
  "id": "bdb81514-9753-4e53-b946-abf25f986f4c",
  "type": "email",
  "status": "COMPLETED",
  "retryCount": 0,
  "maxRetries": 3,
  "createdAt": "2026-10-24T20:00:00Z",
  "scheduledAt": "2026-10-24T20:00:00Z"
}
```

Returns HTTP `200 OK` for an existing job and `404 Not Found` for an unknown job ID.

## How Concurrent Processing Works

The application polls Redis for jobs whose scheduled execution time has arrived.

Each ready job is claimed using a Redis Lua script. The script selects and removes one eligible job from the queue as a single atomic operation.

This prevents two workers from successfully claiming the same queued job.

A fixed thread pool with three workers executes the claimed jobs asynchronously. A semaphore limits the number of active or submitted jobs to three worker slots.

Job information is stored separately in Redis and remains available through the REST API.

## Retry Logic

When a job fails, the application checks its retry count and schedules another execution if retries remain.

It uses exponential backoff:

| Retry | Delay     |
| ----- | --------- |
| 1     | 2 seconds |
| 2     | 4 seconds |
| 3     | 8 seconds |

After the third retry fails, the job is marked `FAILED` and removed from the pending queue.

The current implementation supports these simulated job types:

- `email` - Simulates a two-second task.
- `report` - Simulates a four-second task.
- `fail` - Intentionally throws an exception to demonstrate retry handling.
- Other types - Simulate a one-second task.

These jobs simulate background work rather than sending actual emails or generating real reports.

## Getting Started

### Prerequisites

- Java 21
- Docker Desktop
- Maven wrapper (included)

### 1. Clone the Repository

```bash
git clone https://github.com/Mihir16x/Distributed-Job-Scheduler.git
cd distributed-job-scheduler
```

Replace `YOUR_USERNAME` with your GitHub username.

### 2. Start Redis

```bash
docker run -d --name job-scheduler-redis -p 6379:6379 redis:7
```

If the container already exists but is stopped:

```bash
docker start job-scheduler-redis
```

Verify Redis is running:

```bash
docker exec job-scheduler-redis redis-cli ping
```

Expected response:

```text
PONG
```

### 3. Start the Application

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS or Linux:

```bash
./mvnw spring-boot:run
```

The API starts at:

```text
http://localhost:8080
```

### 4. Submit a Test Job

Using PowerShell:

```powershell
$body = @{ type = "email" } | ConvertTo-Json

$job = Invoke-RestMethod `
    -Uri "http://localhost:8080/jobs" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body

$job | Format-List
```

### 5. Check Job Status

```powershell
Invoke-RestMethod `
    -Uri "http://localhost:8080/jobs/$($job.id)" `
    -Method Get
```

After processing, the job should have a status of `COMPLETED`.

To test retries, submit a job with type `fail` and check its status after the retry attempts finish.

## Testing

The project includes seven JUnit tests:

1. Spring Boot application context loads.
2. Jobs are stored and retrieved from Redis.
3. Jobs can be claimed and marked `RUNNING`.
4. Concurrent claim attempts do not duplicate a queued job.
5. Failed jobs are scheduled for retry.
6. Retry delays increase exponentially.
7. Jobs become permanently failed and leave the queue after exhausting retries.

Run all tests on Windows:

```powershell
.\mvnw.cmd clean test
```

The tests require Redis to be running locally on port `6379`.

Latest local result:

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Current Limitations

This project is intended as a lightweight demonstration of Redis-backed scheduling and concurrent background processing.

- Workers run as threads within a Spring Boot application.
- The application has not been validated across multiple deployed server instances.
- Workers do not use leases or heartbeats.
- Jobs claimed immediately before an application crash may not be recovered automatically.
- The application does not guarantee exactly-once job execution.
- Job types are simulated workloads for demonstration.
- Tests use a local Redis instance rather than an isolated production-like environment.

## Future Improvements

- Worker leases and recovery for abandoned jobs.
- Multi-instance deployment and distributed integration testing.
- Dead-letter queue for failed jobs.
- Monitoring and job execution metrics.
- Authentication and request validation.
- Dedicated test containers for Redis integration tests.
