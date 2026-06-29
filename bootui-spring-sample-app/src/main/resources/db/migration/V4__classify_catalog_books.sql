-- Pending Flyway migration for the BootUI demo: seed book tags.
insert into catalog_tag (name)
values ('computing'),
       ('history');

insert into catalog_book_tag (book_id, tag_id)
values (1, 1),
       (2, 1),
       (1, 2);
