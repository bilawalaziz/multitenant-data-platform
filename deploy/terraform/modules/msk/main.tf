resource "aws_msk_configuration" "this" {
  name           = "${var.name}-config"
  kafka_versions = [var.kafka_version]

  # Every topic is replicated to all 3 brokers and needs 2 in sync to ack (acks=all).
  server_properties = <<-EOT
    default.replication.factor=3
    min.insync.replicas=2
  EOT
}

resource "aws_cloudwatch_log_group" "broker" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.data_subnet_ids
    security_groups = [var.security_group_id]
    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_size
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.kms_key_arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    unauthenticated = false
    sasl {
      scram = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker.name
      }
    }
  }
}

resource "aws_msk_scram_secret_association" "this" {
  cluster_arn     = aws_msk_cluster.this.arn
  secret_arn_list = [var.scram_secret_arn]
}
