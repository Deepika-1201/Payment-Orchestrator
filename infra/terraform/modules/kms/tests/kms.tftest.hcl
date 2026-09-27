# terraform test in modules/kms (ADR-028): mocked apply, no AWS credentials.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

variables {
  name = "pg-prod-mum"
}

run "keys_rotate_and_cannot_be_deleted_quickly" {
  command = apply

  assert {
    condition     = aws_kms_key.data.enable_key_rotation && aws_kms_key.telemetry.enable_key_rotation
    error_message = "Customer-managed keys rotate yearly."
  }

  assert {
    condition     = aws_kms_key.data.deletion_window_in_days == 30 && aws_kms_key.telemetry.deletion_window_in_days == 30
    error_message = "Scheduled key deletion waits 30 days, time to notice a mistake."
  }

  assert {
    condition     = aws_kms_alias.data.name == "alias/pg-prod-mum-data" && aws_kms_alias.telemetry.name == "alias/pg-prod-mum-telemetry"
    error_message = "Aliases name the region and purpose."
  }
}
