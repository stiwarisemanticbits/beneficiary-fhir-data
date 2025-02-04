locals {
  env             = terraform.workspace
  service         = "pipeline"
  variant         = "ccw"
  region          = data.aws_region.current.name
  resource_prefix = "bfd-${local.env}"

  kms_key_arn = var.aws_kms_key_arn
  kms_key_id  = var.aws_kms_key_id

  nonsensitive_ccw_map = zipmap(
    data.aws_ssm_parameters_by_path.nonsensitive_ccw.names,
    nonsensitive(data.aws_ssm_parameters_by_path.nonsensitive_ccw.values)
  )
  nonsensitive_ccw_config = {
    for key, value in local.nonsensitive_ccw_map
    : element(split("/", key), length(split("/", key)) - 1) => value
  }
  repeater_invoke_rate = local.nonsensitive_ccw_config["slis_repeater_lambda_invoke_rate"]

  lambda_update_slis = "update_slis" # resources related to this lambda are also named "this" in some cases
  lambda_repeater    = "repeater"
  lambdas = {
    for label, name in {
      "${local.lambda_update_slis}" = "update-${local.service}-slis",
      "${local.lambda_repeater}"    = "${local.service}-metrics-repeater"
    } :
    label => {
      name      = name
      full_name = "${local.resource_prefix}-${name}"
      src       = replace(name, "-", "_")
    }
  }

  metrics_namespace = "${local.resource_prefix}/bfd-${local.service}"
}

resource "aws_lambda_permission" "this" {
  statement_id   = "${local.lambdas[local.lambda_update_slis].full_name}-allow-sns"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.this.function_name
  principal      = "sns.amazonaws.com"
  source_arn     = data.aws_sns_topic.this.arn
  source_account = var.account_id
}

resource "aws_sns_topic_subscription" "this" {
  topic_arn = data.aws_sns_topic.this.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.this.arn
}

resource "aws_lambda_function" "this" {
  function_name = local.lambdas[local.lambda_update_slis].full_name

  description = join("", [
    "Puts new CloudWatch Metric Data related to BFD Pipline SLIs whenever a new file is uploaded ",
    "to corresponding Done/Incoming paths in the ${local.env} BFD ETL S3 Bucket ",
    "(${data.aws_s3_bucket.etl.id})"
  ])

  tags = {
    Name = local.lambdas[local.lambda_update_slis].full_name
  }

  kms_key_arn = local.kms_key_arn

  # Ensures that only _one_ instance of this Lambda can run at any given time. This stops the
  # possible duplicate submissions to the first available time metric that could otherwise occur
  # if two instances of this Lambda are invoked close together. This _does_ take from our total
  # reserved concurrent executions, so we should investigate an even more robust method of stopping
  # duplicate submissions
  reserved_concurrent_executions = 1

  filename         = data.archive_file.lambda_src[local.lambda_update_slis].output_path
  source_code_hash = data.archive_file.lambda_src[local.lambda_update_slis].output_base64sha256
  architectures    = ["x86_64"]
  handler          = "${local.lambdas[local.lambda_update_slis].src}.handler"
  memory_size      = 128
  package_type     = "Zip"
  runtime          = "python3.11"
  layers           = ["arn:aws:lambda:${local.region}:017000801446:layer:AWSLambdaPowertoolsPythonV2:60"]
  timeout          = 300
  environment {
    variables = {
      METRICS_NAMESPACE       = local.metrics_namespace
      ETL_BUCKET_ID           = data.aws_s3_bucket.etl.id
      RIF_AVAILABLE_DDB_TBL   = aws_dynamodb_table.update_slis_rif_available.name
      LOAD_AVAILABLE_DDB_TBL  = aws_dynamodb_table.update_slis_load_available.name
      POWERTOOLS_LOG_LEVEL    = "info"
      POWERTOOLS_SERVICE_NAME = local.lambdas[local.lambda_update_slis].name
    }
  }

  role = aws_iam_role.lambda[local.lambda_update_slis].arn
}

resource "aws_lambda_function_event_invoke_config" "this" {
  function_name = aws_lambda_function.this.function_name
  # This Lambda is invoked by SNS, which invokes the Lambda asynchronously. By default, AWS Lambda
  # retries failing Functions twice before dropping the event, but because this Lambda has side
  # effects (creates metrics, posts events to a queue, etc.) we don't want to retry if it fails.
  maximum_retry_attempts = 0
}

resource "aws_dynamodb_table" "update_slis_rif_available" {
  name      = "${local.lambdas[local.lambda_update_slis].full_name}-rif-available"
  hash_key  = "group_iso_str"
  range_key = "rif"
  # The number of writes/reads, combined with the average size of approx. 120 bytes per-item, means
  # that it's highly likely this table will cost less than $1 (probably _far_ less) per-month. It
  # would be _far_ more expensive for this table to be set to PROVISIONED
  billing_mode = "PAY_PER_REQUEST"

  server_side_encryption {
    enabled     = true
    kms_key_arn = local.kms_key_arn
  }

  point_in_time_recovery {
    enabled = true
  }

  attribute {
    name = "group_iso_str"
    type = "S"
  }

  attribute {
    name = "rif"
    type = "S"
  }

  ttl {
    attribute_name = "dynamo_expiration"
    enabled        = true
  }
}

resource "aws_dynamodb_table" "update_slis_load_available" {
  name     = "${local.lambdas[local.lambda_update_slis].full_name}-load-available"
  hash_key = "group_iso_str"
  # The number of writes/reads, combined with the average size of approx. 120 bytes per-item, means
  # that it's highly likely this table will cost less than $1 (probably _far_ less) per-month. It
  # would be _far_ more expensive for this table to be set to PROVISIONED
  billing_mode = "PAY_PER_REQUEST"

  server_side_encryption {
    enabled     = true
    kms_key_arn = local.kms_key_arn
  }

  point_in_time_recovery {
    enabled = true
  }

  attribute {
    name = "group_iso_str"
    type = "S"
  }

  ttl {
    attribute_name = "dynamo_expiration"
    enabled        = true
  }
}

resource "aws_scheduler_schedule_group" "repeater" {
  name = "${local.lambdas[local.lambda_repeater].full_name}-lambda-schedules"
}

resource "aws_scheduler_schedule" "repeater" {
  name       = "${local.lambdas[local.lambda_repeater].full_name}-every-${replace(local.repeater_invoke_rate, " ", "-")}"
  group_name = aws_scheduler_schedule_group.repeater.name

  flexible_time_window {
    mode = "OFF"
  }

  schedule_expression = "rate(${local.repeater_invoke_rate})"

  target {
    arn      = aws_lambda_function.repeater.arn
    role_arn = aws_iam_role.scheduler_assume_role.arn
  }
}

resource "aws_lambda_function" "repeater" {
  function_name = local.lambdas[local.lambda_repeater].full_name

  description = join("", [
    "Invoked by rate schedules in the ${aws_scheduler_schedule_group.repeater.name} schedule ",
    "group. When invoked, this Lambda re-submits the latest values of select CCW Pipeline ",
    "metrics to corresponding \"-repeating\" metrics for SLO Alarms"
  ])

  tags = {
    Name = local.lambdas[local.lambda_repeater].full_name
  }

  kms_key_arn = local.kms_key_arn

  filename         = data.archive_file.lambda_src[local.lambda_repeater].output_path
  source_code_hash = data.archive_file.lambda_src[local.lambda_repeater].output_base64sha256
  architectures    = ["x86_64"]
  handler          = "${local.lambdas[local.lambda_repeater].src}.handler"
  memory_size      = 128
  package_type     = "Zip"
  runtime          = "python3.11"
  timeout          = 300
  environment {
    variables = {
      METRICS_NAMESPACE = local.metrics_namespace
    }
  }

  role = aws_iam_role.lambda[local.lambda_repeater].arn
}
