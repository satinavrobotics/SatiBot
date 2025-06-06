package com.satinavrobotics.satibot.mapManagement;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.jetbrains.annotations.NotNull;
import com.satinavrobotics.satibot.databinding.ItemMapBinding;

import java.util.List;

public class MapAdapter extends RecyclerView.Adapter<MapAdapter.ViewHolder> {

    private List<Map> mValues;
    private final Context mContext;
    private final OnItemClickListener<Map> itemClickListener;
    private String selectedMapId;

    public interface OnItemClickListener<T> {
        void onItemClick(T item);
        void onMapDelete(T item);
    }

    public MapAdapter(List<Map> items, Context context, OnItemClickListener<Map> itemClickListener) {
        mValues = items;
        mContext = context;
        this.itemClickListener = itemClickListener;
    }

    @NotNull
    @Override
    public ViewHolder onCreateViewHolder(@NotNull ViewGroup parent, int viewType) {
        return new ViewHolder(
                ItemMapBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.mItem = mValues.get(position);
        holder.mapTitle.setText(mValues.get(position).getName());

        // Special handling for Earth map and No Map option
        String mapId = mValues.get(position).getId();
        boolean isSpecialMap = "earth".equals(mapId) || "no_map".equals(mapId);
        if (isSpecialMap) {
            // Don't show anchor count for special maps
            holder.anchorCount.setVisibility(View.GONE);
            // Don't show delete icon for special maps
            holder.deleteMap.setVisibility(View.GONE);
        } else {
            holder.anchorCount.setVisibility(View.VISIBLE);
            holder.anchorCount.setText(mValues.get(position).getAnchorCount() + " anchors");
            holder.deleteMap.setVisibility(View.VISIBLE);
        }

        // Set selection state
        boolean isSelected = selectedMapId != null && selectedMapId.equals(holder.mItem.getId());
        holder.mItem.setSelected(isSelected);
        holder.mapSelected.setChecked(isSelected);

        // Make the entire item clickable
        holder.itemView.setOnClickListener(v -> itemClickListener.onItemClick(holder.mItem));
        // Only set delete click listener if not a special map
        if (!isSpecialMap) {
            holder.deleteMap.setOnClickListener(v -> itemClickListener.onMapDelete(holder.mItem));
        }
    }

    @Override
    public int getItemCount() {
        return mValues.size();
    }

    public void setItems(List<Map> mapList) {
        this.mValues = mapList;
        notifyDataSetChanged();
    }

    public void setSelectedMapId(String mapId) {
        this.selectedMapId = mapId;
        notifyDataSetChanged();
    }

    public String getSelectedMapId() {
        return selectedMapId;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView mapTitle;
        public final TextView anchorCount;
        public final ImageView deleteMap;
        public final RadioButton mapSelected;
        public Map mItem;

        public ViewHolder(ItemMapBinding binding) {
            super(binding.getRoot());
            mapTitle = binding.mapTitle;
            anchorCount = binding.anchorCount;
            deleteMap = binding.deleteMap;
            mapSelected = binding.mapSelected;
        }
    }
}
