locals {
  log_groups = {
    access = "/bfd/${var.env}/bfd-server/access.txt"
  }

  endpoints = {
    "all": "*",
    "metadata": "*/metadata*",
    "coverageAll": "*/Coverage*",
    "coverageRead": "*/Coverage/*",
    "coverageByPatientId": "*/Coverage*beneficiary=*",
    "patientAll": "*/Patient*",
    "patientRead": "*/Patient/*",
    "patientById": "*/Patient*_id=*",
    "patientByIdentifier": "*/Patient*identifier=*hicnHash*",
    "eobAll": "*/ExplanationOfBenefit*",
    "eobRead": "*/ExplanationOfBenefit/*",
    "eobReadCarrier": "*/ExplanationOfBenefit/carrier*",
    "eobReadDme": "*/ExplanationOfBenefit/dme*",
    "eobReadHha": "*/ExplanationOfBenefit/hha*",
    "eobReadHospice": "*/ExplanationOfBenefit/hospice*",
    "eobReadInpatient": "*/ExplanationOfBenefit/inpatient*",
    "eobReadOutpatient": "*/ExplanationOfBenefit/outpatient*",
    "eobReadPde": "*/ExplanationOfBenefit/pde*",
    "eobReadSnf": "*/ExplanationOfBenefit/snf*",
    "eobByPatientId": "*/ExplanationOfBenefit*patient=*",
    "eobByPaged": "*/ExplanationOfBenefit*_count=*",
    "eobByType":  "*/ExplanationOfBenefit*type=*",
  }
}

# Count requests per endpoint, per partner
#
resource "aws_cloudwatch_log_metric_filter" "http-requests-count" {
  for_each       = local.endpoints
  name           = "bfd-${var.env}/bfd-server/http-requests/count/${each.key}/${var.metric_config.partner_name}"
  pattern        = "[remote_host_name, remote_logical_username, remote_authenticated_user = \"${var.metric_config.partner_regex}\", timestamp, request = \"${each.value}\", query_string, status_code, bytes, duration_milliseconds, original_query_id, original_query_counter, original_query_timestamp, developer, developer_name, application_id, application, user_id, user, beneficiary_id]"
  log_group_name = local.log_groups.access

  metric_transformation {
    name          = "http-requests/count/${each.key}/${var.metric_config.partner_name}"
    namespace     = "bfd-${var.env}/bfd-server"
    value         = "1"
    default_value = "0"
  }
}

# Count HTTP 500s per partner
#
resource "aws_cloudwatch_log_metric_filter" "http-requests-count-500" {
  name           = "bfd-${var.env}/bfd-server/http-requests/count-500/${var.metric_config.partner_name}"
  pattern        = "[remote_host_name, remote_logical_username, remote_authenticated_user = \"${var.metric_config.partner_regex}\", timestamp, request, query_string, status_code = 500, bytes, duration_milliseconds, original_query_id, original_query_counter, original_query_timestamp, developer, developer_name, application_id, application, user_id, user, beneficiary_id]"
  log_group_name = local.log_groups.access

  metric_transformation {
    name          = "http-requests/count-500/${var.metric_config.partner_name}"
    namespace     = "bfd-${var.env}/bfd-server"
    value         = "1"
    default_value = "0"
  }
}

# Count HTTP non-2XXs per partner
#
resource "aws_cloudwatch_log_metric_filter" "http-requests-count-not-2xx" {
  name           = "bfd-${var.env}/bfd-server/http-requests/count-not-2xx/${var.metric_config.partner_name}"
  pattern        = "[remote_host_name, remote_logical_username, remote_authenticated_user = \"${var.metric_config.partner_regex}\", timestamp, request, query_string, status_code != 2*, bytes, duration_milliseconds, original_query_id, original_query_counter, original_query_timestamp, developer, developer_name, application_id, application, user_id, user, beneficiary_id]"
  log_group_name = local.log_groups.access

  metric_transformation {
    name          = "http-requests/count-not-2xx/${var.metric_config.partner_name}"
    namespace     = "bfd-${var.env}/bfd-server"
    value         = "1"
    default_value = "0"
  }
}

# Latency per endpoint, per partner
#
resource "aws_cloudwatch_log_metric_filter" "http-requests-latency" {
  for_each       = local.endpoints
  name           = "bfd-${var.env}/bfd-server/http-requests/latency/${each.key}/${var.metric_config.partner_name}"
  pattern        = "[remote_host_name, remote_logical_username, remote_authenticated_user = \"${var.metric_config.partner_regex}\", timestamp, request = \"${each.value}\", query_string, status_code, bytes, duration_milliseconds, original_query_id, original_query_counter, original_query_timestamp, developer, developer_name, application_id, application, user_id, user, beneficiary_id]"
  log_group_name = local.log_groups.access

  metric_transformation {
    name          = "http-requests/latency/${each.key}/${var.metric_config.partner_name}"
    namespace     = "bfd-${var.env}/bfd-server"
    value         = "$duration_milliseconds"
    default_value = null
  }
}

# Count latency over 6s per partner
#
resource "aws_cloudwatch_log_metric_filter" "http-requests-latency-over-6000" {
  name           = "bfd-${var.env}/bfd-server/http-requests/latency-over-6000/${var.metric_config.partner_name}"
  pattern        = "[remote_host_name, remote_logical_username, remote_authenticated_user = \"${var.metric_config.partner_regex}\", timestamp, request, query_string, status_code, bytes, duration_milliseconds > 6000, original_query_id, original_query_counter, original_query_timestamp, developer, developer_name, application_id, application, user_id, user, beneficiary_id]"
  log_group_name = local.log_groups.access

  metric_transformation {
    name          = "http-requests/latency-over-6000/${var.metric_config.partner_name}"
    namespace     = "bfd-${var.env}/bfd-server"
    value         = "1"
    default_value = "0"
  }
}
