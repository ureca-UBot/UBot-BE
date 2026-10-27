FROM amazonlinux:2023

ARG TERRAFORM_VERSION=1.16.4

RUN dnf install -y \
      unzip \
      less \
      groff \
      awscli-2 \
    && curl -fsSLo /tmp/terraform.zip \
      "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_amd64.zip" \
    && unzip /tmp/terraform.zip -d /usr/local/bin \
    && rm -f /tmp/terraform.zip \
    && dnf clean all

ENTRYPOINT ["terraform"]