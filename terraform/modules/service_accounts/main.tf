resource "google_service_account" "sa_demo_service_runner" {
  account_id   = "${var.prefix}-${local.sa_demo_service_runner.name}"
  display_name = local.sa_demo_service_runner.display_name
}


resource "google_project_iam_member" "sa_demo_service_runner_roles" {
  for_each           = local.sa_demo_service_runner.roles
  project            = var.project
  role               = each.value
  member             = "serviceAccount:${google_service_account.sa_demo_service_runner.account_id}@${var.project}.iam.gserviceaccount.com"
  depends_on         = [google_service_account.sa_demo_service_runner]
}


resource "google_service_account" "sa_demo_service_invoker" {
  account_id   = "${var.prefix}-${local.sa_demo_service_invoker.name}"
  display_name = local.sa_demo_service_invoker.display_name
}


resource "google_project_iam_member" "sa_demo_service_invoker_roles" {
  for_each           = local.sa_demo_service_invoker.roles
  project            = var.project
  role               = each.value
  member             = "serviceAccount:${google_service_account.sa_demo_service_invoker.account_id}@${var.project}.iam.gserviceaccount.com"
  depends_on         = [google_service_account.sa_demo_service_invoker]
}


# Add specific IAM binding for the demo secret
resource "google_secret_manager_secret_iam_member" "demo_secret_accessor" {
  secret_id = "${var.prefix}-demo-secret"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.sa_demo_service_runner.account_id}@${var.project}.iam.gserviceaccount.com"
  depends_on = [google_service_account.sa_demo_service_runner]
}


locals {
  sa_demo_service_runner = {
    name = "sa-demo-service-runner",
    display_name = "Demo Service Runner",
    roles = {
      "logWriter" = "roles/logging.logWriter",
      "secretAccessor" = "roles/secretmanager.secretAccessor",
    }
  }
  sa_demo_service_invoker = {
    name = "sa-demo-service-invoker",
    display_name = "Demo Service Invoker",
    roles = {
      "runInvoker" = "roles/run.invoker"
    }
  }
}
