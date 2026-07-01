-- Second Flyway migration so the panel shows a non-trivial migration history.
insert into catalog_author (name)
values ('Ada Lovelace'),
       ('Alan Turing');

insert into catalog_book (title, author_id)
values ('Notes on the Analytical Engine', 1),
       ('On Computable Numbers', 2);
