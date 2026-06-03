-- Flyway-managed schema (separate from the Liquibase-managed "inventory" tables).
-- Flyway tracks these migrations in its own flyway_schema_history table.
create table catalog_author (
    id bigint generated always as identity primary key,
    name varchar(120) not null
);

create table catalog_book (
    id bigint generated always as identity primary key,
    title varchar(200) not null,
    author_id bigint references catalog_author (id)
);
