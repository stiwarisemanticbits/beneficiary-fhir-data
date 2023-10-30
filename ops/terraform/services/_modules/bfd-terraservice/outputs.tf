output "env" {
  description = "The solution's environment name. Generally, `terraform.workspace`"
  sensitive   = false
  value       = var.environment_name
}

output "is_ephemeral_env" {
  description = "Returns true when environment is _ephemeral_, false when _established_"
  sensitive   = false
  value       = var.environment_name != local.seed_env
}

output "seed_env" {
  description = "The solution's source environment. For established environments this is equal to the environment's name"
  sensitive   = false
  value       = local.seed_env
}

output "default_tags" {
  value = merge(var.additional_tags, {
    Environment    = local.seed_env
    application    = "bfd"
    business       = "oeda"
    stack          = var.environment_name
    Terraform      = true
    tf_module_root = var.relative_module_root
  })
  sensitive = false
}

output "latest_bfd_release" {
  description = "This is the latest CMSgov/beneficiary-fhir-data release. Excludes Pre-Releases."
  sensitive   = false
  value       = try(jsondecode(data.http.latest_bfd_release.response_body).tag_name, null)
}
