package com.hiddenramblings.tagmo.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.hiddenramblings.tagmo.BrowserActivity;
import com.hiddenramblings.tagmo.R;
import com.hiddenramblings.tagmo.TagMo;
import com.hiddenramblings.tagmo.amiibo.Amiibo;
import com.hiddenramblings.tagmo.nfc.TagUtil;
import com.hiddenramblings.tagmo.nfc.TagWriter;
import com.hiddenramblings.tagmo.settings.BrowserSettings;
import com.hiddenramblings.tagmo.settings.SettingsFragment;

import java.util.ArrayList;

public class EliteBrowserAdapter extends RecyclerView.Adapter<EliteBrowserAdapter.AmiiboVewHolder> {
    private final BrowserSettings settings;
    private final OnAmiiboClickListener listener;
    private final ArrayList<Amiibo> amiibos;

    public EliteBrowserAdapter(BrowserSettings settings, OnAmiiboClickListener listener, ArrayList<Amiibo> amiibos) {
        this.settings = settings;
        this.listener = listener;

        this.amiibos = amiibos;
    }

    @Override
    public int getItemCount() {
        return amiibos.size();
    }

    @Override
    public long getItemId(int i) {
        return amiibos.get(i).id;
    }

    public Amiibo getItem(int i) {
        return amiibos.get(i);
    }

    @Override
    public int getItemViewType(int position) {
        return settings.getAmiiboView();
    }

    @NonNull
    @Override
    public AmiiboVewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        switch (viewType) {
            case BrowserActivity.VIEW_TYPE_COMPACT:
                return new CompactViewHolder(parent, settings, listener);
            case BrowserActivity.VIEW_TYPE_LARGE:
                return new LargeViewHolder(parent, settings, listener);
            case BrowserActivity.VIEW_TYPE_SIMPLE:
            default:
                return new SimpleViewHolder(parent, settings, listener);
        }
    }

    @Override
    public void onBindViewHolder(final AmiiboVewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static abstract class AmiiboVewHolder extends RecyclerView.ViewHolder {
        private final BrowserSettings settings;
        private final OnAmiiboClickListener listener;

        public final TextView txtError;
        public final TextView txtName;
        public final TextView txtTagId;
        public final TextView txtAmiiboSeries;
        public final TextView txtAmiiboType;
        public final TextView txtGameSeries;
        // public final TextView txtCharacter;
        public final TextView txtPath;
        public final ImageView imageAmiibo;

        Amiibo amiiboItem = null;

        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>() {
            @Override
            public void onLoadStarted(@Nullable Drawable placeholder) {
                imageAmiibo.setVisibility(View.GONE);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                imageAmiibo.setVisibility(View.GONE);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {

            }

            @Override
            public void onResourceReady(@NonNull Bitmap resource, Transition transition) {
                imageAmiibo.setImageBitmap(resource);
                imageAmiibo.setVisibility(View.VISIBLE);
            }
        };

        public AmiiboVewHolder(View itemView, BrowserSettings settings, OnAmiiboClickListener listener) {
            super(itemView);

            this.settings = settings;
            this.listener = listener;
            this.itemView.setOnClickListener(view -> {
                if (AmiiboVewHolder.this.listener != null) {
                    AmiiboVewHolder.this.listener.onAmiiboClicked(
                            amiiboItem, getAbsoluteAdapterPosition());
                }
            });
            this.itemView.setOnLongClickListener(v -> {
                if (AmiiboVewHolder.this.listener != null) {
                    AmiiboVewHolder.this.listener.onAmiiboLongClicked(
                            amiiboItem, getAbsoluteAdapterPosition());
                }
                return true;
            });
            this.txtError = itemView.findViewById(R.id.txtError);
            this.txtName = itemView.findViewById(R.id.txtName);
            this.txtTagId = itemView.findViewById(R.id.txtTagId);
            this.txtAmiiboSeries = itemView.findViewById(R.id.txtAmiiboSeries);
            this.txtAmiiboType = itemView.findViewById(R.id.txtAmiiboType);
            this.txtGameSeries = itemView.findViewById(R.id.txtGameSeries);
            // this.txtCharacter = itemView.findViewById(R.id.txtCharacter);
            this.txtPath = itemView.findViewById(R.id.txtPath);
            this.imageAmiibo = itemView.findViewById(R.id.imageAmiibo);
            if (this.imageAmiibo != null) {
                this.imageAmiibo.setOnClickListener(view -> {
                    if (AmiiboVewHolder.this.listener != null) {
                        AmiiboVewHolder.this.listener.onAmiiboImageClicked(
                                amiiboItem, getAbsoluteAdapterPosition());
                    }
                });
            }
        }

        @SuppressLint("SetTextI18n")
        void bind(final Amiibo amiibo) {
            this.amiiboItem = amiibo;

            String tagInfo = null;
            String amiiboHexId = "";
            String amiiboName = "";
            String amiiboSeries = "";
            String amiiboType = "";
            String gameSeries = "";
            // String character = "";
            String amiiboImageUrl = null;
            boolean isAmiibo = amiibo != null;

            if (isAmiibo) {
                amiiboHexId = TagUtil.amiiboIdToHex(amiibo.id);
                amiiboImageUrl = amiibo.getImageUrl();
                if (amiibo.name != null)
                    amiiboName = amiibo.name;
                if (amiibo.getAmiiboSeries() != null)
                    amiiboSeries = amiibo.getAmiiboSeries().name;
                if (amiibo.getAmiiboType() != null)
                    amiiboType = amiibo.getAmiiboType().name;
                if (amiibo.getGameSeries() != null)
                    gameSeries = amiibo.getGameSeries().name;
                // if (amiibo.getCharacter() != null)
                //     gameSeries = amiibo.getCharacter().name;
            }

            String query = settings.getQuery().toLowerCase();
            String position = String.valueOf(TagWriter.getValueFromPosition(
                    getAbsoluteAdapterPosition()));

            if (tagInfo == null) {
                this.txtError.setVisibility(View.GONE);
            } else {
                setAmiiboInfoText(this.txtError, tagInfo, false);
            }
            if (isAmiibo) {
                setAmiiboInfoText(this.txtName, position + ": " + amiiboName, false);
                setAmiiboInfoText(this.txtTagId, boldStartText(amiiboHexId, query),
                        tagInfo != null);
                setAmiiboInfoText(this.txtAmiiboSeries, boldMatchingText(amiiboSeries, query),
                        tagInfo != null);
                setAmiiboInfoText(this.txtAmiiboType, boldMatchingText(amiiboType, query),
                        tagInfo != null);
                setAmiiboInfoText(this.txtGameSeries, boldMatchingText(gameSeries, query),
                        tagInfo != null);
                // setAmiiboInfoText(this.txtCharacter, boldMatchingText(character, query),
                //         tagInfo != null);
            } else {
                this.txtName.setVisibility(View.VISIBLE);
                this.txtName.setText(TagMo.getStringRes(R.string.blank_tag_number, position));
                this.txtTagId.setVisibility(View.GONE);
                this.txtAmiiboSeries.setVisibility(View.GONE);
                this.txtAmiiboType.setVisibility(View.GONE);
                this.txtGameSeries.setVisibility(View.GONE);
                // this.txtCharacter.setVisibility(View.GONE);
            }

            if (this.imageAmiibo != null) {
                this.imageAmiibo.setVisibility(View.GONE);
                Glide.with(itemView).clear(target);
                Glide.with(itemView)
                        .setDefaultRequestOptions(new RequestOptions().onlyRetrieveFromCache(onlyRetrieveFromCache()))
                        .asBitmap()
                        .load(amiiboImageUrl != null ? amiiboImageUrl: R.mipmap.ic_launcher)
                        .into(target);
            }
        }

        boolean onlyRetrieveFromCache() {
            String imageNetworkSetting = settings.getImageNetworkSettings();
            if (SettingsFragment.IMAGE_NETWORK_NEVER.equals(imageNetworkSetting)) {
                return true;
            } else if (SettingsFragment.IMAGE_NETWORK_WIFI.equals(imageNetworkSetting)) {
                ConnectivityManager cm = (ConnectivityManager)
                        itemView.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                return activeNetwork == null || activeNetwork.getType() != ConnectivityManager.TYPE_WIFI;
            } else {
                return false;
            }
        }

        SpannableStringBuilder boldMatchingText(String text, String query) {
            SpannableStringBuilder str = new SpannableStringBuilder(text);
            if (query.isEmpty())
                return str;

            text = text.toLowerCase();
            int j = 0;
            while (j < text.length()) {
                int i = text.indexOf(query, j);
                if (i == -1)
                    break;

                j = i + query.length();
                str.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, j, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return str;
        }

        SpannableStringBuilder boldStartText(String text, String query) {
            SpannableStringBuilder str = new SpannableStringBuilder(text);
            if (!query.isEmpty() && text.toLowerCase().startsWith(query)) {
                str.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, query.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return str;
        }

        void setAmiiboInfoText(TextView textView, CharSequence text, boolean hasTagInfo) {
            if (hasTagInfo) {
                textView.setVisibility(View.GONE);
            } else {
                textView.setVisibility(View.VISIBLE);
                if (text.length() == 0) {
                    textView.setText(R.string.unknown);
                    textView.setEnabled(false);
                } else {
                    textView.setText(text);
                    textView.setEnabled(true);
                }
            }
        }
    }

    static class SimpleViewHolder extends AmiiboVewHolder {
        public SimpleViewHolder(ViewGroup parent, BrowserSettings settings, OnAmiiboClickListener listener) {
            super(
                    LayoutInflater.from(parent.getContext()).inflate(
                            R.layout.amiibo_simple_card, parent, false),
                    settings,
                    listener
            );
        }
    }

    static class CompactViewHolder extends AmiiboVewHolder {
        public CompactViewHolder(ViewGroup parent, BrowserSettings settings, OnAmiiboClickListener listener) {
            super(
                    LayoutInflater.from(parent.getContext()).inflate(
                            R.layout.amiibo_compact_card, parent, false),
                    settings,
                    listener
            );
        }
    }

    static class LargeViewHolder extends AmiiboVewHolder {
        public LargeViewHolder(ViewGroup parent, BrowserSettings settings, OnAmiiboClickListener listener) {
            super(
                    LayoutInflater.from(parent.getContext()).inflate(
                            R.layout.amiibo_large_card, parent, false),
                    settings,
                    listener
            );
        }
    }

    public interface OnAmiiboClickListener {
        void onAmiiboClicked(Amiibo amiibo, int position);

        void onAmiiboLongClicked(Amiibo amiibo, int position);

        void onAmiiboImageClicked(Amiibo amiibo, int position);
    }
}