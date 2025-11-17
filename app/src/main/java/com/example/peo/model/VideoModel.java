package com.example.peo.model;

public class VideoModel {
    private String path;
    private String name;
    private long lastModified;
    private String lastModifiedString;

    // Updated Constructor
    public VideoModel(String path, String name, long lastModified, String lastModifiedString) {
        this.path = path;
        this.name = name;
        this.lastModified = lastModified;
        this.lastModifiedString = lastModifiedString;
    }

    public String getPath() { return path; }
    public String getName() { return name; }
    public long getLastModified() { return lastModified; }
    public String getLastModifiedString() { return lastModifiedString; }
}