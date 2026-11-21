variable "name" {
  description = "Alias suffix: the key is reachable as alias/<name>"
  type        = string
}

variable "deletion_window_in_days" {
  description = "Waiting period before a scheduled key deletion completes (7-30)"
  type        = number
  default     = 30
}
