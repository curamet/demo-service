resource "google_storage_bucket" "bucket" {
  name          = "${var.prefix}-curamet-bucket"
  location      = "EU"
  force_destroy = true

  uniform_bucket_level_access = true
    lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type = "Delete"
    }
  }
  
  lifecycle_rule {
    condition {
      age = 14
      matches_storage_class = ["STANDARD"]
    }
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }
  }
  
  lifecycle_rule {
    condition {
      age = 1
      matches_prefix = ["temp/", "tmp/"] 
    }
    action {
      type = "Delete"
    }
  }
  
  lifecycle_rule {
    condition {
      days_since_noncurrent_time = 90
    }
    action {
      type          = "SetStorageClass"
      storage_class = "ARCHIVE"
    }
  }
}

resource "google_storage_bucket_iam_member" "bucket_iam_member" {
  bucket = google_storage_bucket.bucket.name
  role = "roles/storage.objectCreator"
  member = "serviceAccount:${var.runner_service_account}"
  depends_on = [google_storage_bucket.bucket]
}
