locals {
  victor_ops_url                    = local.sensitive_common_config["victor_ops_url"]
  ec2_failing_instances_runbook_url = local.sensitive_common_config["alarm_ec2_failing_instances_runbook_url"]
  cloudwatch_sns_topic_policy_spec  = <<-EOF
{
  "Version": "2008-10-17",
  "Id": "__default_policy_ID",
  "Statement": [
    {
      "Sid": "Allow_Publish_Alarms",
      "Effect": "Allow",
      "Principal": {
        "Service": ["cloudwatch.amazonaws.com"]
      },
      "Action": "sns:Publish",
      "Resource": "%s"
    },
    {
      "Sid": "__default_statement_ID",
      "Effect": "Allow",
      "Principal": {
        "AWS": "*"
      },
      "Action": [
        "SNS:GetTopicAttributes",
        "SNS:SetTopicAttributes",
        "SNS:AddPermission",
        "SNS:RemovePermission",
        "SNS:DeleteTopic",
        "SNS:Subscribe",
        "SNS:ListSubscriptionsByTopic",
        "SNS:Publish",
        "SNS:Receive"
      ],
      "Resource": "%s",
      "Condition": {
        "StringEquals": {
          "AWS:SourceOwner": "${local.account_id}"
        }
      }
    }
  ]
}
EOF
}

# SNS Topic for Alarm actions when Alarms transition from OK -> ALARM, indicating that the Alarm
# condition has been met and that something is wrong and needs attention from the on-call
resource "aws_sns_topic" "victor_ops_alert" {
  name              = "bfd-${local.env}-victor-ops-alert"
  kms_master_key_id = local.kms_key_id
}

resource "aws_sns_topic_policy" "victor_ops_alert" {
  arn    = aws_sns_topic.victor_ops_alert.arn
  policy = format(local.cloudwatch_sns_topic_policy_spec, aws_sns_topic.victor_ops_alert.arn, aws_sns_topic.victor_ops_alert.arn)
}

resource "aws_sns_topic_subscription" "victor_ops_alert" {
  protocol               = "https"
  topic_arn              = aws_sns_topic.victor_ops_alert.arn
  endpoint               = local.victor_ops_url
  endpoint_auto_confirms = true
}

# SNS Topic for Alarm actions when Alarms transition from ALARM -> OK, indicating that the Alarm
# condition has been resolved
resource "aws_sns_topic" "victor_ops_ok" {
  name              = "bfd-${local.env}-victor-ops-ok"
  kms_master_key_id = local.kms_key_id
}

resource "aws_sns_topic_policy" "victor_ops_ok" {
  arn    = aws_sns_topic.victor_ops_ok.arn
  policy = format(local.cloudwatch_sns_topic_policy_spec, aws_sns_topic.victor_ops_ok.arn, aws_sns_topic.victor_ops_ok.arn)
}

resource "aws_sns_topic_subscription" "victor_ops_ok" {
  topic_arn              = aws_sns_topic.victor_ops_ok.arn
  protocol               = "https"
  endpoint               = local.victor_ops_url
  endpoint_auto_confirms = true
}

resource "aws_cloudwatch_metric_alarm" "ec2_failing_instances" {
  alarm_name          = "bfd-${local.env}-ec2-failing-instances"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  datapoints_to_alarm = 1
  evaluation_periods  = 1
  threshold           = 1
  treat_missing_data  = "missing"

  alarm_description = join("", [
    "At least 1 (see Alarm value for exact number) EC2 instance is failing its status checks.\n",
    "See ${local.ec2_failing_instances_runbook_url} for instructions on resolving this alert."
  ])

  metric_query {
    period      = 60
    expression  = "SELECT SUM(StatusCheckFailed) FROM \"AWS/EC2\""
    id          = "q1"
    label       = "StatusCheckFailed Sum"
    return_data = true
  }

  alarm_actions = [aws_sns_topic.victor_ops_alert.arn]
  ok_actions    = [aws_sns_topic.victor_ops_ok.arn]
}
