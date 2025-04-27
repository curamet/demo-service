resource "null_resource" "run_shell_script" {
  provisioner "local-exec" {
    command = "./push-docker-image.sh"
    environment = {
      IMAGE_URL  = local.image_url
    }
  }

  triggers = {
    version = var.run_version
  }
}

resource "google_cloud_run_v2_service" "run_service" {
  deletion_protection = false
  name = "${var.prefix}-${var.run_service_name}"
  location = var.region
  template {
      containers {
        image = local.image_url
        env {
          name = "BUCKET_NAME"
          value = var.bucket_name
        }
        env {
          name = "SECRET_NAME"
          value = var.secret_name
        }
        env {
          name = "PROJECT_ID"
          value = var.project
        }
      }
      service_account = var.service_account
  }
  depends_on = [null_resource.run_shell_script]
}

// temp: for testing now
resource "google_cloud_run_service_iam_member" "public_invoker" {
  location = var.region
  service  = google_cloud_run_v2_service.run_service.name
  role     = "roles/run.invoker"
  member   = "allUsers"
  depends_on = [google_cloud_run_v2_service.run_service]
}

locals {
  image_url = "europe-docker.pkg.dev/${var.project}/${var.repository_id}/${var.prefix}-${var.run_service_name}:${var.run_version}"
}
