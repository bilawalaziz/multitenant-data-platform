variable "endpoint" {
  description = "AWS Emulator endpoint as seen from the Terraform container, which shares the emulator's network namespace (the certificate only names 127.0.0.1)"
  type        = string
  default     = "https://127.0.0.1:9297"
}
