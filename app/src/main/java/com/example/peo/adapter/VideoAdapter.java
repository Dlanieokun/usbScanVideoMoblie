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

import com.example.peo.R;
// import com.example.peo.VideoPlayerActivity; // No longer needed
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
        holder.tvDate.setText(video.getLastModifiedString());

        // Set default icon and start loading thumbnail in background
        holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
        new ThumbnailLoaderTask(holder.ivThumbnail, video.getPath()).execute();

    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvDate;
        ImageView ivThumbnail;
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvVideoName);
            tvDate = itemView.findViewById(R.id.tvVideoDate);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
        }
    }

    /**
     * AsyncTask to load video thumbnail off the main thread using MediaMetadataRetriever.
     */
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
                imageView.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        }
    }
}