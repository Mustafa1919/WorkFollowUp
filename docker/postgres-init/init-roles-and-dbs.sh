#!/bin/sh
set -e

# app_migrator zaten POSTGRES_USER olarak var (bootstrap superuser, DB/tablo sahibi).
# app_runtime'i burada NON-SUPERUSER olarak olusturuyoruz: superuser'lar
# FORCE ROW LEVEL SECURITY dahil RLS'i her zaman bypass eder, bu yuzden runtime
# rolu asla superuser olmamali (bkz. AbstractIntegrationTest, ayni desen).
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE app_runtime WITH LOGIN NOSUPERUSER PASSWORD 'app_runtime';
    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO app_runtime;
    GRANT USAGE, CREATE ON SCHEMA public TO app_runtime;
    ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;
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
