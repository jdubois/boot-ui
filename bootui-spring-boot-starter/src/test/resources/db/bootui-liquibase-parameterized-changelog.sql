--liquibase formatted sql

--changeset bootui:create-parameterized-table
CREATE TABLE ${bootui.test.table} (id INT PRIMARY KEY);
