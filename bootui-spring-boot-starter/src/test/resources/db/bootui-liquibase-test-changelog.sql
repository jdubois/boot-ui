--liquibase formatted sql

--changeset bootui:create-widget
CREATE TABLE bootui_widget (id INT PRIMARY KEY, name VARCHAR(64));

--changeset bootui:create-gadget
CREATE TABLE bootui_gadget (id INT PRIMARY KEY);
