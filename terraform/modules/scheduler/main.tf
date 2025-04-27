resource "google_cloud_scheduler_job" "demo_service_scheduler" {
  name             = "${var.prefix}-demo-service-scheduler"
  description      = "Scheduler to hit the root endpoint of Demo Service"
  schedule         = var.schedule
  time_zone        = "UTC"
  attempt_deadline = "320s"
  region           = var.region

  http_target {
    http_method = "GET"
    uri         = "${var.run_service_url}/"
    
    oidc_token {
      service_account_email = var.invoker_service_account
      audience              = var.run_service_url
    }
  }
} 