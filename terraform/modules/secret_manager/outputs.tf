output "secret_name" {
  value = google_secret_manager_secret.secret.name
}

output "latest_secret_version" {
  value = data.google_secret_manager_secret_version.secret-latest.version
}
