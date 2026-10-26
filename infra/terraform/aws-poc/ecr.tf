# CD 작업이 BE 이미지를 커밋 SHA 태그로 ECR에 올리고 다이제스트로 배포함
resource "aws_ecr_repository" "be" {
  name                 = "ubot-be-aws-poc"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = false
  }
}

resource "aws_ecr_lifecycle_policy" "be" {
  repository = aws_ecr_repository.be.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the latest 20 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 20
      }
      action = { type = "expire" }
    }]
  })
}
