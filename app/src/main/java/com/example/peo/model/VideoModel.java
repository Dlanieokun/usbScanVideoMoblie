package com.example.peo.model;

public class VideoModel {
    private String path;
    private String name;
    private long lastModified;
    private String lastModifiedString;
    private String camera;
    private String status_upload;

    public VideoModel(String path, String name, long lastModified, String lastModifiedString, String camera, String status_upload) {
        this.path = path;
        this.name = name;
        this.lastModified = lastModified;
        this.lastModifiedString = lastModifiedString;
        this.camera = camera;
        this.status_upload = status_upload;
    }

    // Getters
    public String getPath() { return path; }
    public String getName() { return name; }
    public long getLastModified() { return lastModified; }

    // FIX: Added the missing getter method
    public String getLastModifiedString() { return lastModifiedString; }

    public String getStatus_upload() { return status_upload; }

    // Setter
    public void setStatus_upload(String status_upload) { this.status_upload = status_upload; }

    // Required for Queue.contains() check
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoModel that = (VideoModel) o;
        // Videos are considered equal if their file name and URI path match
        return name.equals(that.name) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(path, name);
    }
}