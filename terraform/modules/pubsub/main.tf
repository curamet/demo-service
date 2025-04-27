data "google_project" "project" {}

# Assign Publisher role to the Cloud Pub/Sub service account
resource "google_project_iam_member" "pubsub_publisher_role" {
  project = var.project
  role    = "roles/pubsub.publisher"
  member  = "serviceAccount:service-${data.google_project.project.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
}


##############################################
# Dead lettering topic #
resource "google_pubsub_topic" "dead_letter_topic" {
  name = "${var.prefix}-dead-letter-topic"
}

resource "google_pubsub_subscription" "dead_lettering_subscription" {
  name  = "${var.prefix}-dead-lettering-subscription"
  topic = google_pubsub_topic.dead_letter_topic.id
  depends_on = [google_pubsub_topic.dead_letter_topic]
  
  lifecycle {
    ignore_changes = [name]
  }
}
##############################################


##############################################
# demo topic with subscription #
resource "google_pubsub_topic" "demo_topic" {
  name = "${var.prefix}-demo-topic"
  depends_on = [google_pubsub_topic.dead_letter_topic]
  
  lifecycle {
    ignore_changes = [name]
  }
}

resource "google_pubsub_subscription" "subscription_trigger_demo_service" {
  name     = "${var.prefix}-subscription-trigger-demo-service"
  topic    =  google_pubsub_topic.demo_topic.name

  ack_deadline_seconds = 600


  push_config {
    push_endpoint = "${var.run_service_url}/push-trigger"
    oidc_token {
      service_account_email = var.invoker_service_account
    }
  }
  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dead_letter_topic.id
    max_delivery_attempts = 5
  }
  expiration_policy {
    ttl = "" // never
  }

  retry_policy {
    maximum_backoff = "10s"
    minimum_backoff = "5s"
  }

  message_retention_duration = "604800s" // 7 days
  enable_message_ordering    = true

  depends_on = [google_pubsub_topic.demo_topic, google_pubsub_topic.dead_letter_topic]
}

#permissions for subscribing to the dead lettering topic -- VERY IMPORTANT TO ADD TO ALL SUBSCRIPTIONS THAT USE DEAD LETTER
resource "google_pubsub_subscription_iam_member" "dead_lettering_subscriber_permission" {
  project = var.project
  subscription = google_pubsub_subscription.subscription_trigger_demo_service.name
  role = "roles/pubsub.subscriber"
  member = "serviceAccount:service-${data.google_project.project.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
  depends_on = [google_pubsub_subscription.subscription_trigger_demo_service]
}

##############################################

##############################################
# Storage object notification topic with subscription #

// Enable notifications by giving the correct IAM permission to the unique service account.
data "google_storage_project_service_account" "gcs_account" {
}
resource "google_pubsub_topic" "object_notification_topic" {
  name = "${var.prefix}-object-notification-topic"
  depends_on = [google_pubsub_topic.dead_letter_topic]
}

resource "google_pubsub_topic_iam_binding" "binding" {
  topic   = google_pubsub_topic.object_notification_topic.id
  role    = "roles/pubsub.publisher"
  members = ["serviceAccount:${data.google_storage_project_service_account.gcs_account.email_address}"]
  depends_on = [google_pubsub_topic.object_notification_topic]
}

resource "google_storage_notification" "notification" {
  bucket         = var.bucket_name
  payload_format = "JSON_API_V1"
  topic          = google_pubsub_topic.object_notification_topic.id
  event_types    = ["OBJECT_FINALIZE"]
  depends_on = [google_pubsub_topic_iam_binding.binding]
}

resource "google_pubsub_subscription" "subscription_object_notification" {
  name     = "${var.prefix}-subscription-object-notification"
  topic    =  google_pubsub_topic.object_notification_topic.name

  ack_deadline_seconds = 600


  push_config {
    push_endpoint = "${var.run_service_url}/object-notification"
    oidc_token {
      service_account_email = var.invoker_service_account
    }
  }
  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dead_letter_topic.id
    max_delivery_attempts = 5
  }
  expiration_policy {
    ttl = "" // never
  }

  retry_policy {
    maximum_backoff = "10s"
    minimum_backoff = "5s"
  }

  message_retention_duration = "604800s" // 7 days
  enable_message_ordering    = true

  depends_on = [google_pubsub_topic.object_notification_topic, google_pubsub_topic.dead_letter_topic]
}

#permissions for subscribing to the dead lettering topic -- VERY IMPORTANT TO ADD TO ALL SUBSCRIPTIONS THAT USE DEAD LETTER
resource "google_pubsub_subscription_iam_member" "dead_lettering_subscriber_on_permission" {
  project = var.project
  subscription = google_pubsub_subscription.subscription_object_notification.name
  role = "roles/pubsub.subscriber"
  member = "serviceAccount:service-${data.google_project.project.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
  depends_on = [google_pubsub_subscription.subscription_object_notification]
}
##############################################

resource "google_pubsub_subscription" "pull_subscription_demo_service" {
  name  = "${var.prefix}-pull-subscription-demo-service"
  topic = google_pubsub_topic.demo_topic.name

  ack_deadline_seconds = 600

  expiration_policy {
    ttl = ""
  }

  message_retention_duration = "604800s" # 7 days
  enable_message_ordering    = true

  depends_on = [google_pubsub_topic.demo_topic]
}



