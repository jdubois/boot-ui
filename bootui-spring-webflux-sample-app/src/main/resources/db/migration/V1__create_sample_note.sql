-- Flyway-managed "sample_note" table, read by NoteRepository/NoteController.
create table sample_note (
    id bigint generated always as identity primary key,
    title varchar(120) not null,
    body varchar(500) not null
);

insert into sample_note (title, body) values
    ('Welcome', 'This note was seeded by a Flyway migration.'),
    ('Reactive', 'Served through a Mono/Flux endpoint backed by blocking JDBC.');
