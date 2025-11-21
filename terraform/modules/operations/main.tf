provider "google" {
  project = var.project
  region = var.region
}

# enable apis
resource "google_project_service" "enabled_apis" {
  for_each = local.enable_apis

  service                    = each.value
  disable_dependent_services = false
  disable_on_destroy         = false
}


resource "google_artifact_registry_repository" "curamet-repo" {
  repository_id = "${var.prefix}-curamet-repo"
  description   = "example docker repository"
  project       = var.project
  location      = var.region
  format        = "DOCKER"
  depends_on    = [google_project_service.enabled_apis]
}

locals {
  // Go to: https://console.cloud.google.com/apis/library to see apis list
  enable_apis = {
    "artifactregistry.googleapis.com" = "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com"       = "cloudbuild.googleapis.com",
    "cloudRun.googleapis.com"         = "run.googleapis.com",
    "secretmanager.googleapis.com"    = "secretmanager.googleapis.com",
    "cloudstorage.googleapis.com"      = "storage-component.googleapis.com",
  }
}
