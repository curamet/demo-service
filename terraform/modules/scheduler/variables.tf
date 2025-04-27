variable "project" {
  description = "Google Cloud project ID"
  type        = string
}

variable "region" {
  description = "Google Cloud region"
  type        = string
}

variable "prefix" {
  description = "Prefix for all resources"
  type        = string
}

variable "schedule" {
  description = "Cron schedule expression for the scheduler job"
  type        = string
  default     = "*/10 * * * *"  # Every 10 minutes by default
}

variable "run_service_url" {
  description = "URL of the Cloud Run service to invoke"
  type        = string
}

variable "invoker_service_account" {
  description = "Service account email used to invoke the Cloud Run service"
  type        = string
} 