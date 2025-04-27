variable "project" {
  type = string
}
variable "region" {
  type    = string
  default = "europe-west1"
}

variable "project_number" {
  type = string
}

variable "location" {
  type = string
  default = "EU"
}

variable "envname" {
  type = string
  default = "dev"
}

variable "prefix" {
  type = string
  default = "dev"
}


variable "run_version" {
    type = string
    default = null 
}

variable "run_service_name" {
    type = string
}
