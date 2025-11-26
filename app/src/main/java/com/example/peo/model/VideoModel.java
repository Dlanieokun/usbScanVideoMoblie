package com.example.peo.model;

public class VideoModel {
    private String path;
    private String name;
    private long lastModified;
    private String lastModifiedString;

    private String upload_check_in;
    private String status_upload;

    public VideoModel(String path, String name, long lastModified, String lastModifiedString,
                      String upload_check_in, String status_upload) {
        this.path = path;
        this.name = name;
        this.lastModified = lastModified;
        this.lastModifiedString = lastModifiedString;
        this.upload_check_in = upload_check_in;
        this.status_upload = status_upload;
    }

    // Getters
    public String getPath() { return path; }
    public String getName() { return name; }
    public String getUpload_check_in() { return upload_check_in; }
    public String getStatus_upload() { return status_upload; }
    public long getLastModified() { return lastModified; }
    public String getLastModifiedString() { return lastModifiedString; }
}
