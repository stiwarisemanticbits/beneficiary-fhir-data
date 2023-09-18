module "terraservice" {
  source = "../_modules/bfd-terraservice"

  environment_name     = terraform.workspace
  relative_module_root = "ops/terraform/services/eft"
  additional_tags = {
    Layer = local.layer
    Name  = local.full_name
    role  = local.service
  }
}

locals {
  default_tags     = module.terraservice.default_tags
  env              = module.terraservice.env
  seed_env         = module.terraservice.seed_env
  is_ephemeral_env = module.terraservice.is_ephemeral_env

  service   = "eft"
  layer     = "data"
  full_name = "bfd-${local.env}-${local.service}"

  eft_partners    = jsondecode(nonsensitive(data.aws_ssm_parameter.partners_list_json.value))
  ssm_hierarchies = concat(["bfd"], local.eft_partners)
  ssm_services    = ["common", local.service]
  ssm_all_paths = {
    for x in setproduct(local.ssm_hierarchies, local.ssm_services) :
    "${x[0]}-${x[1]}" => {
      hierarchy = x[0]
      service   = x[1]
    }
  }
  # This returns an object with nested keys in the following format: ssm_config.<top-level hierarchy
  # name>.<service name>; for example, to get a parameter named "vpc_name" in BFD's hierarchy in the
  # "common" service, it would be: local.ssm_config.bfd.common.vpc_name.
  # FUTURE: Refactor this out into a distinct module much like bfd-terraservice above
  ssm_config = {
    # This would be much easier if Terraform had a reduce() or if merge() was a deep merge instead
    # of shallow. We must do a double iteration otherwise we end up with objects with the same
    # top-level keys but different inner keys (i.e. {bfd = common = ...} and {bfd = eft = ...}).
    # Using merge() to merge those would result in only the last object being taken ({bfd = eft = ...})
    for hierarchy in local.ssm_hierarchies :
    hierarchy => {
      for key, meta in local.ssm_all_paths :
      # We know all services within a given hierarchy are distinct, so we can just iterate over them
      # and build a full object at once.
      "${meta.service}" => zipmap(
        [
          for name in concat(
            data.aws_ssm_parameters_by_path.nonsensitive[key].names,
            data.aws_ssm_parameters_by_path.sensitive[key].names
          ) : element(split("/", name), length(split("/", name)) - 1)
        ],
        nonsensitive(
          concat(
            data.aws_ssm_parameters_by_path.nonsensitive[key].values,
            data.aws_ssm_parameters_by_path.sensitive[key].values
          )
        )
      )
      if hierarchy == meta.hierarchy
    }
  }

  # SSM Lookup
  kms_key_alias = local.ssm_config.bfd.common["kms_key_alias"]
  vpc_name      = local.ssm_config.bfd.common["vpc_name"]

  subnet_ip_reservations = jsondecode(
    local.ssm_config.bfd[local.service]["subnet_to_ip_reservations_nlb_json"]
  )
  host_key              = local.ssm_config.bfd[local.service]["sftp_transfer_server_host_private_key"]
  eft_r53_hosted_zone   = local.ssm_config.bfd[local.service]["r53_hosted_zone"]
  eft_user_sftp_pub_key = local.ssm_config.bfd[local.service]["sftp_eft_user_public_key"]
  eft_user_username     = local.ssm_config.bfd[local.service]["sftp_eft_user_username"]
  eft_partners_config = {
    for partner in local.eft_partners :
    partner => {
      bucket_iam_assumer_arn             = local.ssm_config[partner][local.service]["bucket_iam_assumer_arn"]
      bucket_home_path                   = trim(local.ssm_config[partner][local.service]["bucket_home_path"], "/")
      bucket_notifs_subscriber_principal = lookup(local.ssm_config[partner][local.service], "bucket_notifications_subscriber_principal_arn", null)
      bucket_notifs_subscriber_arn       = lookup(local.ssm_config[partner][local.service], "bucket_notifications_subscriber_arn", null)
      bucket_notifs_subscriber_protocol  = lookup(local.ssm_config[partner][local.service], "bucket_notifications_subscriber_protocol", null)
    }
  }
  eft_partners_with_s3_notifs = [
    for partner, config in local.eft_partners_config : partner
    if config.bucket_notifs_subscriber_principal != null &&
    config.bucket_notifs_subscriber_arn != null &&
    config.bucket_notifs_subscriber_protocol != null
  ]

  # Data source lookups

  account_id     = data.aws_caller_identity.current.account_id
  vpc_id         = data.aws_vpc.this.id
  kms_key_id     = data.aws_kms_key.cmk.arn
  sftp_port      = 22
  logging_bucket = "bfd-${local.seed_env}-logs-${local.account_id}"

  # For some reason, the transfer server endpoint service does not support us-east-1b and instead
  # opts to support us-east-1d. In order to enable support for this sub-az in the future
  # automatically (if transfer server VPC endpoints begin to support 1c), we filter our desired
  # subnets against the supported availability zones taking only those that belong to supported azs
  available_endpoint_azs = setintersection(
    data.aws_vpc_endpoint_service.transfer_server.availability_zones,
    values(data.aws_subnet.this)[*].availability_zone
  )
  available_endpoint_subnets = [
    for subnet in values(data.aws_subnet.this)
    : subnet if contains(local.available_endpoint_azs, subnet.availability_zone)
  ]
}

resource "aws_s3_bucket" "this" {
  bucket = local.full_name
}

resource "aws_s3_bucket_notification" "bucket_notifications" {
  count = length(local.eft_partners_with_s3_notifs) > 0 ? 1 : 0

  bucket = aws_s3_bucket.this.id

  dynamic "topic" {
    for_each = toset(local.eft_partners_with_s3_notifs)

    content {
      events        = ["s3:ObjectCreated:*"]
      filter_prefix = "${local.eft_user_username}/${local.eft_partners_config[topic.key].bucket_home_path}/"
      id            = "${local.full_name}-s3-event-notifications-${topic.key}"
      topic_arn     = aws_sns_topic.bucket_notifications[topic.key].arn
    }
  }
}

resource "aws_sns_topic" "bucket_notifications" {
  for_each = toset(local.eft_partners_with_s3_notifs)

  name              = "${local.full_name}-s3-event-notifications-${each.key}"
  kms_master_key_id = local.kms_key_id
}

resource "aws_sns_topic_policy" "bucket_notifications" {
  for_each = toset(local.eft_partners_with_s3_notifs)

  arn = aws_sns_topic.bucket_notifications[each.key].arn
  policy = jsonencode(
    {
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "Allow_Publish_from_S3"
          Effect    = "Allow"
          Principal = { Service = "s3.amazonaws.com" }
          Action    = "SNS:Publish"
          Resource  = aws_sns_topic.bucket_notifications[each.key].arn
          Condition = {
            ArnLike = {
              "aws:SourceArn" = "${aws_s3_bucket.this.arn}"
            }
          }
        },
        {
          Sid       = "Allow_Subscribe_from_${each.key}"
          Effect    = "Allow"
          Principal = { AWS = local.eft_partners_config[each.key].bucket_notifs_subscriber_principal }
          Action    = ["SNS:Subscribe", "SNS:Receive"]
          Resource  = aws_sns_topic.bucket_notifications[each.key].arn
          Condition = {
            StringEquals = {
              "sns:Protocol" = local.eft_partners_config[each.key].bucket_notifs_subscriber_protocol
            }
            "ForAllValues:StringEquals" = {
              "sns:Endpoint" = [local.eft_partners_config[each.key].bucket_notifs_subscriber_arn]
            }
          }
        }
      ]
    }
  )
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration {
    status = "Disabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id = "${local.full_name}-72hour-object-retention"

    # An empty filter means that this lifecycle applies to _all_ objects within the bucket.
    filter {}

    expiration {
      days = 3 # This bucket has no versioning and so objects will be permanently deleted on expiry
    }

    status = "Enabled"
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = jsonencode(
    {
      Version = "2012-10-17",
      Statement = [
        {
          Sid       = "AllowSSLRequestsOnly",
          Effect    = "Deny",
          Principal = "*",
          Action    = "s3:*",
          Resource = [
            "${aws_s3_bucket.this.arn}",
            "${aws_s3_bucket.this.arn}/*"
          ],
          Condition = {
            Bool = {
              "aws:SecureTransport" = "false"
            }
          }
        }
      ]
    }
  )
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      kms_master_key_id = local.kms_key_id
      sse_algorithm     = "aws:kms"
    }

    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_logging" "this" {
  bucket = aws_s3_bucket.this.id

  target_bucket = local.logging_bucket
  target_prefix = "${local.full_name}_s3_access_logs/"
}

resource "aws_ec2_subnet_cidr_reservation" "this" {
  for_each = local.subnet_ip_reservations

  cidr_block       = "${each.value}/32"
  reservation_type = "explicit"
  subnet_id        = data.aws_subnet.this[each.key].id

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_lb" "this" {
  name                             = "${local.full_name}-nlb"
  internal                         = true
  enable_cross_zone_load_balancing = true
  load_balancer_type               = "network"
  tags                             = { Name = "${local.full_name}-nlb" }

  dynamic "subnet_mapping" {
    for_each = local.available_endpoint_subnets

    content {
      subnet_id            = subnet_mapping.value.id
      private_ipv4_address = local.subnet_ip_reservations[subnet_mapping.value.tags["Name"]]
    }
  }
}

resource "aws_route53_record" "nlb_alias" {
  name    = "${local.env}.${local.service}.${data.aws_route53_zone.this.name}"
  type    = "A"
  zone_id = data.aws_route53_zone.this.zone_id

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}

resource "aws_lb_target_group" "nlb_to_vpc_endpoint" {
  name            = "${local.full_name}-nlb-to-vpce"
  port            = local.sftp_port
  protocol        = "TCP"
  target_type     = "ip"
  ip_address_type = "ipv4"
  vpc_id          = local.vpc_id
  tags            = { Name = "${local.full_name}-nlb-to-vpce" }
}

resource "aws_alb_target_group_attachment" "nlb_to_vpc_endpoint" {
  count = length(local.available_endpoint_subnets)

  target_group_arn = aws_lb_target_group.nlb_to_vpc_endpoint.arn
  target_id        = data.aws_network_interface.vpc_endpoint[count.index].private_ip
}

resource "aws_lb_listener" "nlb_to_vpc_endpoint" {
  load_balancer_arn = aws_lb.this.arn
  port              = local.sftp_port
  protocol          = "TCP"
  tags              = { Name = "${local.full_name}-nlb-listener" }

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.nlb_to_vpc_endpoint.arn
  }
}

resource "aws_security_group" "nlb" {
  name        = "${local.full_name}-nlb"
  description = "Allow access to the ${local.service} network load balancer"
  vpc_id      = local.vpc_id
  tags        = { Name = "${local.full_name}-nlb" }

  ingress {
    from_port       = local.sftp_port
    to_port         = local.sftp_port
    protocol        = "tcp"
    cidr_blocks     = [data.aws_vpc.this.cidr_block]
    description     = "Allow ingress from SFTP traffic"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.vpn.id]
  }

  egress {
    from_port   = local.sftp_port
    to_port     = local.sftp_port
    protocol    = "tcp"
    cidr_blocks = [for ip in data.aws_network_interface.vpc_endpoint[*].private_ip : "${ip}/32"]
  }
}

resource "aws_security_group" "vpc_endpoint" {
  name        = "${local.full_name}-vpc-endpoint"
  description = "Allow ingress and egress from ${aws_lb.this.name}"
  vpc_id      = local.vpc_id
  tags        = { Name = "${local.full_name}-vpc-endpoint" }

  ingress {
    from_port   = local.sftp_port
    to_port     = local.sftp_port
    protocol    = "tcp"
    cidr_blocks = [for ip in aws_lb.this.subnet_mapping[*].private_ipv4_address : "${ip}/32"]
    description = "Allow ingress from SFTP traffic from NLB"
  }

  egress {
    from_port   = local.sftp_port
    to_port     = local.sftp_port
    protocol    = "tcp"
    cidr_blocks = [for ip in aws_lb.this.subnet_mapping[*].private_ipv4_address : "${ip}/32"]
    description = "Allow egress from SFTP traffic from NLB"
  }
}

resource "aws_transfer_server" "this" {
  domain                 = "S3"
  endpoint_type          = "VPC_ENDPOINT"
  host_key               = local.host_key
  identity_provider_type = "SERVICE_MANAGED"
  logging_role           = aws_iam_role.logs.arn
  protocols              = ["SFTP"]
  security_policy_name   = "TransferSecurityPolicy-2020-06"
  tags                   = { Name = "${local.full_name}-sftp" }

  endpoint_details {
    vpc_endpoint_id = aws_vpc_endpoint.this.id
  }
}

resource "aws_transfer_user" "eft_user" {
  server_id = aws_transfer_server.this.id
  role      = aws_iam_role.eft_user.arn
  user_name = local.eft_user_username
  tags      = { Name = "${local.full_name}-sftp-user-${local.eft_user_username}" }

  home_directory_type = "LOGICAL"

  home_directory_mappings {
    entry  = "/"
    target = "/${aws_s3_bucket.this.id}/${local.eft_user_username}"
  }
}

resource "aws_transfer_ssh_key" "eft_user" {
  depends_on = [
    aws_transfer_user.eft_user
  ]

  server_id = aws_transfer_server.this.id
  user_name = aws_transfer_user.eft_user.user_name
  body      = local.eft_user_sftp_pub_key
}

resource "aws_vpc_endpoint" "this" {
  ip_address_type = "ipv4"

  private_dns_enabled = false
  security_group_ids  = [aws_security_group.vpc_endpoint.id]
  service_name        = data.aws_vpc_endpoint_service.transfer_server.service_name
  subnet_ids          = local.available_endpoint_subnets[*].id
  vpc_endpoint_type   = "Interface"
  vpc_id              = local.vpc_id
  tags                = { Name = "${local.full_name}-sftp-endpoint" }
}
