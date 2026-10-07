package com.example.resources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 * A {@code FileInputStream} subclass the leak-walk test defines in a class loader of its own, so a tracked instance
 * keeps that loader reachable for as long as anything keeps the instance.
 */
public class LeakyStream extends FileInputStream {

    public LeakyStream(File file) throws FileNotFoundException {
        super(file);
    }
}
