output "run_service_url" {
    value = google_cloud_run_v2_service.run_service.uri
}

output "url" {
  value = google_cloud_run_v2_service.run_service.uri
}
