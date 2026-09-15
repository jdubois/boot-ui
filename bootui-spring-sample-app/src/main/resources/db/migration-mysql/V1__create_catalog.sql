-- MySQL variant of the Flyway catalog demo; the PostgreSQL/H2 migrations remain unchanged.
create table catalog_author (
    id bigint not null auto_increment primary key,
    name varchar(120) not null
);

create table catalog_book (
    id bigint not null auto_increment primary key,
    title varchar(200) not null,
    author_id bigint,
    constraint fk_catalog_book_author foreign key (author_id) references catalog_author (id)
);
