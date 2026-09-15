-- Pending MySQL migration for the BootUI demo.
create table catalog_tag (
    id bigint not null auto_increment primary key,
    name varchar(80) not null unique
);

create table catalog_book_tag (
    book_id bigint not null,
    tag_id bigint not null,
    primary key (book_id, tag_id),
    constraint fk_catalog_book_tag_book foreign key (book_id) references catalog_book (id),
    constraint fk_catalog_book_tag_tag foreign key (tag_id) references catalog_tag (id)
);
