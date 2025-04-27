output "scheduler_job_name" {
  description = "Name of the created scheduler job"
  value       = google_cloud_scheduler_job.demo_service_scheduler.name
} 