#!/bin/sh
set -e

# app_runtime zaten POSTGRES_USER olarak var; burada sadece app_migrator'i
# ve tracker_dev/staging/prod veritabanlarini ekliyoruz (PHASE_0, Bolum 5 —
# migration ve runtime rolleri ayri tutulur).
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE app_migrator WITH LOGIN PASSWORD 'app_migrator';
EOSQL

for db in tracker_dev tracker_staging tracker_prod; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
        CREATE DATABASE ${db} OWNER app_migrator;
        GRANT CONNECT ON DATABASE ${db} TO app_runtime;
EOSQL
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${db}" <<-EOSQL
        GRANT USAGE, CREATE ON SCHEMA public TO app_runtime;
        ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;
EOSQL
done
