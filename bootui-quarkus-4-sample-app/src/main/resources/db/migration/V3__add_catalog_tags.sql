-- Pending Flyway migration for the BootUI demo: add tags to catalog books.
create table catalog_tag (
    id bigint generated always as identity primary key,
    name varchar(80) not null unique
);

create table catalog_book_tag (
    book_id bigint not null references catalog_book (id),
    tag_id bigint not null references catalog_tag (id),
    primary key (book_id, tag_id)
);
