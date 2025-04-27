provider "google" {
    project = var.project
    region  = var.region
}

# Read version from version.txt - making it dynamic
locals {
  version_from_file = trimspace(file("${path.module}/../version.txt"))
  run_version = var.run_version != null ? var.run_version : local.version_from_file
}

module "operations" {
    source = "./modules/operations"
    project = var.project
    region  = var.region
    prefix  = var.prefix
}

module "secret_manager" {
    source      = "./modules/secret_manager"
    prefix      = var.prefix
    depends_on = [module.operations]
}

module "service_accounts" {
    source           = "./modules/service_accounts"
    project          = var.project
    region           = var.region
    prefix           = var.prefix
    depends_on       = [module.secret_manager]
}


module "storage" {
    source                  = "./modules/storage"
    project                 = var.project
    region                  = var.region
    prefix                  = var.prefix
    runner_service_account  = module.service_accounts.sa_demo_service_runner_email
    depends_on = [module.operations]
}

module "run" {
    source           = "./modules/run"
    project          = var.project
    region           = var.region
    prefix           = var.prefix
    run_version      = local.run_version
    run_service_name = var.run_service_name
    repository_id    = module.operations.repository_id
    service_account  = module.service_accounts.sa_demo_service_runner_email
    bucket_name      = module.storage.bucket_name
    secret_name      = module.secret_manager.secret_name
    depends_on       = [module.service_accounts,module.storage, module.secret_manager, module.operations]
}

module "pubsub" {
    source                  = "./modules/pubsub"
    project                 = var.project
    region                  = var.region
    prefix                  = var.prefix
    run_service_url         = module.run.run_service_url
    invoker_service_account = module.service_accounts.sa_demo_service_invoker_email
    bucket_name             = module.storage.bucket_name
    depends_on = [module.operations, module.storage]
}

module "scheduler" {
    source                  = "./modules/scheduler"
    project                 = var.project
    region                  = var.region
    prefix                  = var.prefix
    run_service_url         = module.run.run_service_url
    invoker_service_account = module.service_accounts.sa_demo_service_invoker_email
    depends_on              = [module.run]
}
