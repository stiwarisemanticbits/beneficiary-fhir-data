locals {
  env = terraform.workspace

  # When the CustomEndpoint is empty, fall back to the ReaderEndpoint
  rds_reader_endpoint = data.external.rds.result["CustomEndpoint"] == "" ? data.external.rds.result["ReaderEndpoint"] : data.external.rds.result["CustomEndpoint"]

  additional_tags = { Layer = var.layer, role = var.role }
  scaleout_asg_capacities = [
    { capacity = length(var.env_config.azs) * 2, metric_lower_bound = 1 * var.scaling_networkin_interval_mb, metric_upper_bound = 2 * var.scaling_networkin_interval_mb },
    { capacity = length(var.env_config.azs) * 3, metric_lower_bound = 2 * var.scaling_networkin_interval_mb, metric_upper_bound = 4 * var.scaling_networkin_interval_mb },
    { capacity = length(var.env_config.azs) * 4, metric_lower_bound = 4 * var.scaling_networkin_interval_mb, metric_upper_bound = null }
  ]

  on_launch_lifecycle_hook_name = "bfd-${local.env}-${var.role}-on-launch"
}

## Security groups
#

# base
resource "aws_security_group" "base" {
  name        = "bfd-${local.env}-${var.role}-base"
  description = "Allow CI access to app servers"
  vpc_id      = var.env_config.vpc_id
  tags        = merge({ Name = "bfd-${local.env}-${var.role}-base" }, local.additional_tags)

  ingress = [] # Make the ingress empty for this SG.

  egress {
    from_port   = 0
    protocol    = "-1"
    to_port     = 0
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# app server
resource "aws_security_group" "app" {
  count       = var.lb_config == null ? 0 : 1
  name        = "bfd-${local.env}-${var.role}-app"
  description = "Allow access to app servers"
  vpc_id      = var.env_config.vpc_id
  tags        = merge({ Name = "bfd-${local.env}-${var.role}-app" }, local.additional_tags)

  ingress {
    from_port       = var.lb_config.port
    to_port         = var.lb_config.port
    protocol        = "tcp"
    security_groups = [var.lb_config.sg]
  }
}

# database
resource "aws_security_group_rule" "allow_db_access" {
  count       = var.db_config == null ? 0 : 1
  type        = "ingress"
  from_port   = 5432
  to_port     = 5432
  protocol    = "tcp"
  description = "Allows access to the ${var.db_config.role} db"

  security_group_id        = var.db_config.db_sg          # The SG associated with each replica
  source_security_group_id = aws_security_group.app[0].id # Every instance in the ASG
}


## Launch Template
#
resource "aws_launch_template" "main" {
  name                   = "bfd-${local.env}-${var.role}"
  description            = "Template for the ${local.env} environment ${var.role} servers"
  vpc_security_group_ids = concat([aws_security_group.base.id, var.mgmt_config.vpn_sg, var.mgmt_config.tool_sg], aws_security_group.app[*].id)
  key_name               = var.launch_config.key_name
  image_id               = var.launch_config.ami_id
  instance_type          = var.launch_config.instance_type
  ebs_optimized          = true

  iam_instance_profile {
    name = var.launch_config.profile
  }

  placement {
    tenancy = "default"
  }

  monitoring {
    enabled = true
  }

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_type           = "gp2"
      volume_size           = var.launch_config.volume_size
      delete_on_termination = true
      encrypted             = true
      kms_key_id            = data.aws_kms_key.master_key.arn
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
    http_tokens                 = "required"
  }

  user_data = base64encode(templatefile("${path.module}/templates/${var.launch_config.user_data_tpl}", {
    env                   = local.env
    port                  = var.lb_config.port
    accountId             = var.launch_config.account_id
    data_server_db_url    = "jdbc:postgresql://${local.rds_reader_endpoint}:5432/fhirdb${var.jdbc_suffix}"
    launch_lifecycle_hook = local.on_launch_lifecycle_hook_name
  }))

  tag_specifications {
    resource_type = "instance"
    tags          = merge({ Name = "bfd-${local.env}-${var.role}" }, local.additional_tags)
  }

  tag_specifications {
    resource_type = "volume"
    tags          = merge({ snapshot = "true", Name = "bfd-${local.env}-${var.role}" }, local.additional_tags)
  }
}


## Autoscaling group
#
resource "aws_autoscaling_group" "main" {
  # Generate a new group on every revision of the launch template.
  # This does a simple version of a blue/green deployment
  name             = "${aws_launch_template.main.name}-${aws_launch_template.main.latest_version}"
  desired_capacity = var.asg_config.desired
  max_size         = var.asg_config.max
  min_size         = var.asg_config.min

  # If an lb is defined, wait for the ELB
  min_elb_capacity          = var.lb_config == null ? null : var.asg_config.min
  wait_for_capacity_timeout = var.lb_config == null ? null : "20m"

  health_check_grace_period = 600 # Temporary, will be lowered when/if lifecycle hooks are implemented
  health_check_type         = var.lb_config == null ? "EC2" : "ELB" # Failures of ELB healthchecks are asg failures
  vpc_zone_identifier       = data.aws_subnet.app_subnets[*].id
  load_balancers            = var.lb_config == null ? [] : [var.lb_config.name]

  launch_template {
    name    = aws_launch_template.main.name
    version = aws_launch_template.main.latest_version
  }

  initial_lifecycle_hook {
    name                 = local.on_launch_lifecycle_hook_name
    default_result       = "ABANDON"
    heartbeat_timeout    = var.asg_config.instance_warmup * 3
    lifecycle_transition = "autoscaling:EC2_INSTANCE_LAUNCHING"
  }

  enabled_metrics = [
    "GroupMinSize",
    "GroupMaxSize",
    "GroupDesiredCapacity",
    "GroupInServiceInstances",
    "GroupPendingInstances",
    "GroupStandbyInstances",
    "GroupTerminatingInstances",
    "GroupTotalInstances",
  ]

  warm_pool {
    pool_state                  = "Stopped"
    min_size                    = var.asg_config.min
    max_group_prepared_capacity = var.asg_config.max_warm
  }

  dynamic "tag" {
    for_each = merge(local.additional_tags, var.env_config.default_tags)
    content {
      key                 = tag.key
      value               = tag.value
      propagate_at_launch = true
    }
  }

  tag {
    key                 = "Name"
    value               = "bfd-${local.env}-${var.role}"
    propagate_at_launch = true
  }

  lifecycle {
    create_before_destroy = true
  }
}


## Autoscaling Policies and Cloudwatch Alarms
#

resource "aws_cloudwatch_metric_alarm" "filtered_networkin_low" {
  alarm_name          = "bfd-${var.role}-${local.env}-networkin-low"
  comparison_operator = "LessThanThreshold"
  datapoints_to_alarm = 10
  evaluation_periods  = 10
  threshold           = 400 * 1000000 # 400 megabytes
  treat_missing_data  = "ignore"
  alarm_actions       = [aws_autoscaling_policy.filtered_networkin_low_scaling.arn]

  metric_query {
    id          = "m1"
    return_data = false

    metric {
      dimensions = {
        AutoScalingGroupName = aws_autoscaling_group.main.name
      }
      metric_name = "NetworkIn"
      namespace   = "AWS/EC2"
      period      = 60
      stat        = "Average"
    }
  }

  metric_query {
    id          = "m2"
    return_data = false

    metric {
      dimensions = {
        AutoScalingGroupName = aws_autoscaling_group.main.name
      }
      metric_name = "NetworkOut"
      namespace   = "AWS/EC2"
      period      = 60
      stat        = "Average"
    }
  }

  metric_query {
    expression  = "IF(m2/m1 > 0.01, m1, 0)"
    id          = "e1"
    label       = "FilteredNetworkIn"
    return_data = true
  }
}

resource "aws_autoscaling_policy" "filtered_networkin_low_scaling" {
  name                    = "bfd-${var.role}-${local.env}-networkin-low-scalein"
  autoscaling_group_name  = aws_autoscaling_group.main.name
  adjustment_type         = "ExactCapacity"
  metric_aggregation_type = "Average"
  policy_type             = "StepScaling"

  # All metric interval bounds are calculated by _adding_ the value of the bound to the threshold
  # of the alarm that this scaling policy operates on. For example, if the alarm threshold is 400MB
  # and the upper bound and lower bounds for a step adjustment are -300MB and -200MB, the step
  # adjustment executes if the metric is greater than 100MB and less than 200MB
  step_adjustment {
    # Large values are always represented in scientific notation by the AWS API, which causes
    # perpetual diffs when applying this module if the value in Terraform is not also in
    # scientific notation. We use format()'s %e format specifier to specify scientific notation
    # and the .0 precision modifier to ensure that Terraform's formatter does not pad the decimal
    # part with 0s
    metric_interval_upper_bound = format("%.0e", -300 * 1000000) # 300 megabytes
    scaling_adjustment          = length(var.env_config.azs)
  }

  step_adjustment {
    metric_interval_lower_bound = format("%.0e", -300 * 1000000) # 300 megabytes
    metric_interval_upper_bound = format("%.0e", -200 * 1000000) # 200 megabytes
    scaling_adjustment          = length(var.env_config.azs) * 2
  }

  step_adjustment {
    metric_interval_lower_bound = format("%.0e", -200 * 1000000) # 200 megabytes
    metric_interval_upper_bound = 0                              # 0 megabytes
    scaling_adjustment          = length(var.env_config.azs) * 3
  }
}

resource "aws_cloudwatch_metric_alarm" "filtered_networkin_high" {
  alarm_name          = "bfd-${var.role}-${local.env}-networkin-high"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  datapoints_to_alarm = 1
  evaluation_periods  = 1
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_autoscaling_policy.filtered_networkin_high_scaling.arn]

  metric_query {
    id          = "m1"
    period      = 0
    return_data = false

    metric {
      dimensions = {
        AutoScalingGroupName = aws_autoscaling_group.main.name
      }
      metric_name = "NetworkIn"
      namespace   = "AWS/EC2"
      period      = 60
      stat        = "Average"
    }
  }
  metric_query {
    id          = "m2"
    period      = 0
    return_data = false

    metric {
      dimensions = {
        AutoScalingGroupName = aws_autoscaling_group.main.name
      }
      metric_name = "NetworkOut"
      namespace   = "AWS/EC2"
      period      = 60
      stat        = "Average"
    }
  }

  metric_query {
    id          = "m3"
    period      = 0
    return_data = false

    metric {
      dimensions = {
        AutoScalingGroupName = aws_autoscaling_group.main.name
      }
      metric_name = "GroupDesiredCapacity"
      namespace   = "AWS/AutoScaling"
      period      = 60
      stat        = "Average"
    }
  }

  metric_query {
    expression  = "IF(m2/m1 > 0.01, m1, 0)"
    id          = "networkin"
    label       = "FilteredNetworkIn"
    period      = 0
    return_data = false
  }

  dynamic "metric_query" {
    for_each = local.scaleout_asg_capacities
    content {
      id    = "e${metric_query.key}"
      label = "Set to ${metric_query.value.capacity} capacity units"
      expression = "IF(${join(" && ", compact([
        "networkin > ${metric_query.value.metric_lower_bound}",
        metric_query.value.metric_upper_bound != null ? "networkin <= ${metric_query.value.metric_upper_bound}" : null,
        "m3 < ${metric_query.value.capacity}"
      ]))}, ${metric_query.key + 1})"
      return_data = false
    }
  }

  metric_query {
    expression  = "MAX([${join(",", [for i in range(length(local.scaleout_asg_capacities)) : "e${i}"])}])"
    id          = "e${length(local.scaleout_asg_capacities)}"
    label       = "ScalingCapacityScalar"
    period      = 0
    return_data = true
  }
}

resource "aws_autoscaling_policy" "filtered_networkin_high_scaling" {
  name                      = "bfd-${var.role}-${local.env}-networkin-high-scaleout"
  autoscaling_group_name    = aws_autoscaling_group.main.name
  estimated_instance_warmup = var.asg_config.instance_warmup
  adjustment_type           = "ExactCapacity"
  metric_aggregation_type   = "Average"
  policy_type               = "StepScaling"

  dynamic "step_adjustment" {
    for_each = local.scaleout_asg_capacities
    content {
      metric_interval_lower_bound = step_adjustment.key
      metric_interval_upper_bound = step_adjustment.key + 1 != length(local.scaleout_asg_capacities) ? step_adjustment.key + 1 : null
      scaling_adjustment          = step_adjustment.value.capacity
    }
  }
}

## Autoscaling Notifications
resource "aws_autoscaling_notification" "asg_notifications" {
  count = var.asg_config.sns_topic_arn != "" ? 1 : 0

  group_names = [aws_autoscaling_group.main.name]

  notifications = [
    "autoscaling:EC2_INSTANCE_LAUNCH",
    "autoscaling:EC2_INSTANCE_TERMINATE",
    "autoscaling:EC2_INSTANCE_LAUNCH_ERROR",
  ]

  topic_arn = var.asg_config.sns_topic_arn
}
