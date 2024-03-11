resource "aws_iam_role" "ccw_rif" {
  count                = local.is_prod ? 1 : 0
  name                 = "bfd-${local.env}-ccw-rif"
  description          = "Role assumed by CCW to read and write to the ${local.env} production and verification ETL buckets."
  max_session_duration = 43200 # max session duration is 12 hours (43200 seconds)- going big for long data-loads
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          AWS = [
            for arn in split(" ", local.sensitive_ccw_service_config["ccw_rif_role_principal_arns"]) : trimspace(arn)
          ]
        }
        Condition = {
          StringEquals = {
            "sts:ExternalId" : local.sensitive_ccw_service_config["ccw_rif_role_external_id"]
          }
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ccw_rif" {
  count      = local.is_prod ? 1 : 0
  role       = aws_iam_role.ccw_rif[0].name
  policy_arn = aws_iam_policy.etl-rw-s3[0].arn
}

resource "aws_iam_policy" "etl-rw-s3" {
  count       = local.is_prod ? 1 : 0
  description = "ETL read-write S3 policy"
  policy = jsonencode(
    {
      "Version" : "2012-10-17",
      "Statement" : [
        {
          "Sid" : "ETLRWKMS",
          "Action" : [
            "kms:Decrypt"
          ],
          "Effect" : "Allow",
          "Resource" : [
            local.kms_key_id
          ]
        },
        {
          "Sid" : "ETLRWBucketList",
          "Action" : [
            "s3:ListBucket"
          ],
          "Effect" : "Allow",
          "Resource" : [
            aws_s3_bucket.this.arn,
            aws_s3_bucket.ccw-verification[0].arn
          ]
        },
        {
          "Sid" : "ETLRWBucketActions",
          "Action" : [
            "s3:GetObject",
            "s3:PutObject"
          ],
          "Effect" : "Allow",
          "Resource" : [
            "${aws_s3_bucket.this.arn}/*",
            "${aws_s3_bucket.ccw-verification[0].arn}/*"
          ]
        }
      ]
  })
}

resource "aws_iam_policy" "aws_cli" {
  description = "AWS cli privileges for the BFD Pipeline instance role."
  name        = "bfd-${local.service}-${local.env}-cli"
  path        = "/"
  policy      = <<-EOF
{
  "Statement": [
    {
      "Action": [
        "ec2:DescribeAvailabilityZones"
      ],
      "Effect": "Allow",
      "Resource": "*"
    }
  ],
  "Version": "2012-10-17"
}
EOF
}

resource "aws_iam_policy" "bfd_pipeline_rif" {
  description = "Allow the BFD Pipeline application to read-write the S3 bucket with the RIF in it."
  name        = "bfd-${local.env}-${local.service}-rw-s3-rif"
  path        = "/"
  policy      = <<-EOF
{
  "Statement": [
    {
      "Action": [
        "kms:Encrypt",
        "kms:Decrypt",
        "kms:ReEncrypt*",
        "kms:GenerateDataKey*",
        "kms:DescribeKey"
      ],
      "Effect": "Allow",
      "Resource": [
        "${local.kms_key_id}"
      ],
      "Sid": "BFDPipelineRWS3RIFKMS"
    },
    {
      "Action": [
        "s3:ListBucket"
      ],
      "Effect": "Allow",
      "Resource": [
        "${aws_s3_bucket.this.arn}"
      ],
      "Sid": "BFDPipelineRWS3RIFListBucket"
    },
    {
      "Action": [
        "s3:ListBucket",
        "s3:GetBucketLocation",
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Effect": "Allow",
      "Resource": [
        "${aws_s3_bucket.this.arn}/*"
      ],
      "Sid": "BFDPipelineRWS3RIFReadWriteObjects"
    }
  ],
  "Version": "2012-10-17"
}
EOF
}

resource "aws_iam_instance_profile" "this" {
  name = "bfd-${local.env}-bfd_${local.service}-profile"
  path = "/"
  role = aws_iam_role.this.name
}

resource "aws_iam_policy" "ssm" {
  description = "Permissions to /bfd/${local.env}/common/nonsensitive, /bfd/${local.env}/${local.service}, /bfd/mgmt/common/sensitive/user SSM hierarchies"
  name        = "bfd-${local.env}-${local.service}-ssm-parameters"
  path        = "/"
  policy = jsonencode(
    {
      "Statement" : [
        {
          "Action" : [
            "ssm:GetParametersByPath",
            "ssm:GetParameters",
            "ssm:GetParameter"
          ],
          "Effect" : "Allow",
          "Resource" : [
            "arn:aws:ssm:us-east-1:${local.account_id}:parameter/bfd/mgmt/common/sensitive/user/*",
            "arn:aws:ssm:us-east-1:${local.account_id}:parameter/bfd/${local.env}/common/sensitive/user/*",
            "arn:aws:ssm:us-east-1:${local.account_id}:parameter/bfd/${local.env}/common/nonsensitive/*",
            "arn:aws:ssm:us-east-1:${local.account_id}:parameter/bfd/${local.env}/${local.service}/*"
          ]
        },
        {
          "Action" : [
            "kms:Decrypt"
          ],
          "Effect" : "Allow",
          "Resource" : concat(local.mgmt_kms_config_key_arns, local.kms_config_key_arns)
        }
      ],
      "Version" : "2012-10-17"
    }
  )
}

resource "aws_iam_role" "this" {
  assume_role_policy    = <<-EOF
{
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Sid": ""
    }
  ],
  "Version": "2012-10-17"
}
EOF
  force_detach_policies = false # TODO unsure of how this works with ephmeral environments just yet...
  managed_policy_arns = [
    aws_iam_policy.aws_cli.arn,
    aws_iam_policy.bfd_pipeline_rif.arn,
    aws_iam_policy.ssm.arn,
    "arn:aws:iam::aws:policy/AmazonElasticFileSystemReadOnlyAccess",
    "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy",
    "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess",
  ]
  max_session_duration = 3600
  name                 = "bfd-${local.env}-bfd_${local.service}-role"
  path                 = "/"
}

resource "aws_iam_policy" "etl_s3_rda_paths_rw" {
  name        = "bfd-${local.env}-${local.service}-etl-s3-rda-paths-rw"
  description = "Read and Write objects within RDA paths"
  policy = jsonencode(
    {
      "Version" : "2012-10-17",
      "Statement" : [
        {
          "Sid" : "ETLRWKMS",
          "Action" : [
            "kms:Decrypt",
            "kms:Encypt",
            "kms:ReEncrypt*",
            "kms:GenerateDataKey*",
            "kms:DescribeKey"
          ],
          "Effect" : "Allow",
          "Resource" : [
            local.kms_key_id
          ]
        },
        {
          "Sid" : "ETLRWBucketList",
          "Action" : [
            "s3:ListBucket"
          ],
          "Effect" : "Allow",
          "Resource" : [
            aws_s3_bucket.this.arn
          ]
        },
        {
          "Sid" : "ETLRWBucketActions",
          "Action" : [
            "s3:GetObject",
            "s3:PutObject",
            "s3:DeleteObject"
          ],
          "Effect" : "Allow",
          "Resource" : [
            "${aws_s3_bucket.this.arn}/RDA-Synthetic/*",
            "${aws_s3_bucket.this.arn}/rda_api_messages/*"
          ]
        }
      ]
  })
}
