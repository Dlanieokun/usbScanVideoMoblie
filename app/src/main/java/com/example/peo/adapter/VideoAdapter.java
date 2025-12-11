package com.example.peo.adapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.peo.R; // Assumed R is available
import com.example.peo.model.VideoModel;

import java.util.List;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.ViewHolder> {

    Context context;
    List<VideoModel> list;

    public VideoAdapter(Context context, List<VideoModel> list) {
        this.context = context;
        this.list = list;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.video_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VideoModel video = list.get(position);
        holder.tvName.setText(video.getName());
        holder.tvDate.setText("File Date: " + video.getLastModifiedString()); // Calls the new getter
        holder.tvUploadStatus.setText("Status: " + video.getStatus_upload());

        // Load thumbnail asynchronously
        new ThumbnailLoaderTask(holder.ivThumbnail, video.getPath()).execute();

        // Optional: Change status text color based on upload status
        switch (video.getStatus_upload().split(" ")[0]) {
            case "UPLOADING": // UPLOADING (0%)
                holder.tvUploadStatus.setTextColor(context.getResources().getColor(android.R.color.holo_orange_dark));
                holder.ivStatusIcon.setImageResource(R.drawable.ic_uploading);
                holder.ivStatusIcon.setColorFilter(context.getResources().getColor(android.R.color.holo_orange_dark));
                break;
            case "COMPLETE": // UPLOAD COMPLETE
                holder.tvUploadStatus.setTextColor(context.getResources().getColor(android.R.color.holo_green_dark));
                holder.ivStatusIcon.setImageResource(R.drawable.ic_uploaded);
                holder.ivStatusIcon.setColorFilter(context.getResources().getColor(android.R.color.holo_green_dark));
                break;
            case "ALREADY": // ALREADY UPLOADED
            case "FAILED": // UPLOAD FAILED
            case "UPLOAD": // UPLOAD FAILED
                holder.tvUploadStatus.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                holder.ivStatusIcon.setImageResource(R.drawable.ic_error);
                holder.ivStatusIcon.setColorFilter(context.getResources().getColor(android.R.color.holo_red_dark));
                break;
            default: // PENDING
                holder.tvUploadStatus.setTextColor(context.getResources().getColor(android.R.color.holo_blue_dark));
                holder.ivStatusIcon.setImageResource(R.drawable.ic_pending);
                holder.ivStatusIcon.setColorFilter(context.getResources().getColor(android.R.color.holo_blue_dark));
                break;
        }
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvDate, tvUploadStatus;
        ImageView ivThumbnail, ivStatusIcon;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvVideoName);
            tvDate = itemView.findViewById(R.id.tvVideoDate);
            tvUploadStatus = itemView.findViewById(R.id.tvUploadStatus);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            ivStatusIcon = itemView.findViewById(R.id.ivStatusIcon);
        }
    }

    // AsyncTask for loading video thumbnails (SAF friendly)
    private class ThumbnailLoaderTask extends AsyncTask<Void, Void, Bitmap> {
        private final ImageView imageView;
        private final String videoPath;

        public ThumbnailLoaderTask(ImageView imageView, String videoPath) {
            this.imageView = imageView;
            this.videoPath = videoPath;
        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            Bitmap bitmap = null;
            try {
                // Note: Context is required for setDataSource with Uri (SAF URIs)
                retriever.setDataSource(context, Uri.parse(videoPath));
                // Capture frame at 1 millisecond
                bitmap = retriever.getFrameAtTime(1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                if (bitmap != null) {
                    int size = 120;
                    bitmap = Bitmap.createScaledBitmap(bitmap, size, size, true);
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // ignore
                }
            }
            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            } else {
                // Fallback to a default icon defined by Android
                imageView.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        }
    }
}