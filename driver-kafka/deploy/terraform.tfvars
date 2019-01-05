public_key_path = "~/.ssh/kafka_aws.pub"
region          = "us-west-1"
ami             = "ami-18726478" // RHEL-7.5

instance_types = {
  "kafka"     = "d2.2xlarge"
  "zookeeper" = "t2.small"
  "client"    = "c5.2xlarge"
}

num_instances = {
  "client"    = 4
  "kafka"     = 3
  "zookeeper" = 3
}
