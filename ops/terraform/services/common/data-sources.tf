data "external" "rds" {
  program = [
    "${path.module}/scripts/rds-cluster-config.sh",   # helper script
    aws_rds_cluster.aurora_cluster.cluster_identifier # verified, positional argument to script
  ]
}

data "aws_availability_zones" "main" {}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_vpc" "main" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

data "aws_subnet" "data" {
  count             = 3
  vpc_id            = data.aws_vpc.main.id
  availability_zone = data.aws_availability_zones.main.names[count.index]

  filter {
    name   = "tag:Layer"
    values = ["data"]
  }
}

data "aws_ssm_parameters_by_path" "nonsensitive" {
  path = "/bfd/${local.env}/${local.service}/nonsensitive"
}

data "aws_ssm_parameters_by_path" "sensitive" {
  path            = "/bfd/${local.env}/${local.service}/sensitive"
  with_decryption = true
}

data "aws_security_group" "vpn" {
  vpc_id = data.aws_vpc.main.id
  filter {
    name   = "tag:Name"
    values = [local.vpn_security_group]
  }
}

data "aws_security_group" "management" {
  vpc_id = data.aws_vpc.main.id
  filter {
    name   = "tag:Name"
    values = [local.management_security_group]
  }
}

data "aws_security_group" "tools" {
  vpc_id = data.aws_vpc.main.id
  filter {
    name   = "tag:Name"
    values = [local.enterprise_tools_security_group]
  }
}

data "aws_kms_key" "cmk" {
  key_id = local.kms_key_alias
}

data "aws_iam_role" "monitoring" {
  name = "rds-monitoring-role"
}
