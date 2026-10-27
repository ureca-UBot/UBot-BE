FROM pgvector/pgvector:0.8.6-pg18-trixie@sha256:78bf48b801e792f99e3ac62b5036fd3876e9be48afda16c1e331af1c75ceb2ff

ARG POSTGIS_VERSION=3.6.4+dfsg-2.pgdg13+1

LABEL org.opencontainers.image.title="UBot PostgreSQL" \
      org.opencontainers.image.description="PostgreSQL 18 with pgvector 0.8.6 and PostGIS 3.6.4"

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        "postgresql-${PG_MAJOR}-postgis-3=${POSTGIS_VERSION}" \
        "postgresql-${PG_MAJOR}-postgis-3-scripts=${POSTGIS_VERSION}" \
    && rm -rf /var/lib/apt/lists/*
