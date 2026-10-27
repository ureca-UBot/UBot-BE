output "state_bucket_name" {
  description = "S3 bucket name for Terraform remote state"
  value       = aws_s3_bucket.terraform_state.bucket
}

output "backend_example" {
  description = "Example backend.hcl content"
  value       = <<-EOT
bucket       = "${aws_s3_bucket.terraform_state.bucket}"
region       = "${var.aws_region}"
encrypt      = true
use_lockfile = true
EOT
}
