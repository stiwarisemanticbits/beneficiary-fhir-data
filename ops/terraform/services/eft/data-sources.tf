data "aws_caller_identity" "current" {}

data "aws_kms_key" "cmk" {
  key_id = local.kms_key_alias
}

data "aws_ssm_parameters_by_path" "nonsensitive_common" {
  path = "/bfd/${local.env}/common/nonsensitive"
}

data "aws_ssm_parameters_by_path" "sensitive_service" {
  path            = "/bfd/${local.env}/${local.service}/sensitive"
  with_decryption = true
}

data "aws_ec2_managed_prefix_list" "vpn" {
  filter {
    name   = "prefix-list-name"
    values = ["cmscloud-vpn"]
  }
}

data "aws_vpc" "this" {
  filter {
    name   = "tag:Name"
    values = [local.vpc_name]
  }
}

data "aws_subnet" "this" {
  for_each = local.subnet_ip_reservations

  vpc_id = local.vpc_id
  filter {
    name   = "tag:Name"
    values = [each.key]
  }
}

data "aws_network_interface" "vpc_endpoint" {
  for_each = toset(aws_vpc_endpoint.this.network_interface_ids)
  id       = each.key
}

data "aws_vpc_endpoint_service" "transfer_server" {
  service_name = "com.amazonaws.us-east-1.transfer.server"
}

data "aws_ssm_parameter" "zone_name" {
  name            = "/bfd/mgmt/common/sensitive/r53_hosted_zone_${local.eft_r53_hosted_zone}_domain"
  with_decryption = true
}

data "aws_ssm_parameter" "zone_is_private" {
  name            = "/bfd/mgmt/common/sensitive/r53_hosted_zone_${local.eft_r53_hosted_zone}_is_private"
  with_decryption = true
}

data "aws_route53_zone" "this" {
  name         = nonsensitive(data.aws_ssm_parameter.zone_name.value)
  private_zone = nonsensitive(data.aws_ssm_parameter.zone_is_private.value)
  tags = {
    "ConfigId" = local.eft_r53_hosted_zone
  }
}
