package com.soundhub.ricardo.soundhub.fragments;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.FrameworkSampleSource;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.gson.Gson;
import com.soundhub.ricardo.soundhub.AppController;
import com.soundhub.ricardo.soundhub.Async.AsyncTrackFetcher;
import com.soundhub.ricardo.soundhub.MainActivity;
import com.soundhub.ricardo.soundhub.R;
import com.soundhub.ricardo.soundhub.Utils.Utils;
import com.soundhub.ricardo.soundhub.adapters.GenresListAdapter;
import com.soundhub.ricardo.soundhub.interfaces.AsyncCustomTaskHandler;
import com.soundhub.ricardo.soundhub.interfaces.OnItemClickListener;
import com.soundhub.ricardo.soundhub.interfaces.OnPlayerStatusChanged;
import com.soundhub.ricardo.soundhub.models.GenreItem;
import com.soundhub.ricardo.soundhub.models.ProgressUpdateItem;
import com.soundhub.ricardo.soundhub.models.TrackLookupResponse;

import java.util.ArrayList;
import java.util.Random;


public class GenresListFragment extends Fragment implements OnItemClickListener, AsyncCustomTaskHandler<ArrayList<TrackLookupResponse>>, ExoPlayer.Listener {


    /**
     * Which item is currently playing
     */
    private int isPlaying;

    /**
     * Player to stream for uri
     */
    private ExoPlayer exoPlayer;

    private static OnPlayerStatusChanged playerStatusListener;
    private GenresListAdapter mAdapter;
    ArrayList<GenreItem> items;

    private ArrayList<TrackLookupResponse> playQueue;

    public GenresListFragment() {
    }

    public static GenresListFragment newInstance(OnPlayerStatusChanged listener) {
        GenresListFragment fragment = new GenresListFragment();
        playerStatusListener = listener;
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_main, container, false);

        RecyclerView mRecyclerView = (RecyclerView) rootView.findViewById(R.id.list_genres);

        final LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity());
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);

        clearStatistics();

        mAdapter = new GenresListAdapter(items, this, getActivity());
        mRecyclerView.setAdapter(mAdapter);

        return rootView;
    }

    private void dispatchPlayer() {

        Uri builtUri = Uri.parse(Utils.soundCloudBaseAddr).buildUpon()
                .appendQueryParameter("client_id", Utils.clientKey)
                .appendQueryParameter("genres", items.get(isPlaying).getGenreValue())
                .build();

        new AsyncTrackFetcher(this).execute(builtUri);


        Log.v("URI", "BUILT URI FOR STREAMING: " + builtUri);
    }

    public void stopStream() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            mAdapter.notifyDataSetChanged();
            Toast.makeText(getActivity(), "Stopped", Toast.LENGTH_SHORT).show();
            playerStatusListener.onPlayerStopped();
            return;
        }


        Toast.makeText(getActivity(), "Not playing", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onItemClick(View view, int position) {

        if (isPlaying == -1) {
            items.get(position).setNowPlaying(true);
            isPlaying = position;
            dispatchPlayer();
        } else if (isPlaying == position) {
            items.get(position).setNowPlaying(!items.get(position).isNowPlaying());
            isPlaying = -1;
            stopStream();
            return;
        } else {
            items.get(isPlaying).setNowPlaying(false);
            items.get(position).setNowPlaying(true);
            isPlaying = position;
            dispatchPlayer();
        }

        mAdapter.notifyDataSetChanged();
        isPlaying = position;
    }

    @Override
    public void onItemLongClick(View view, int position) {

    }

    /**
     * Removes the available statistics and set everything to zero
     */
    public void clearStatistics() {

        SharedPreferences settings = getActivity().getSharedPreferences(
                getResources().getString(R.string.app_name), Context.MODE_PRIVATE);

        if (!settings.contains(Utils.GENRE_STATS_ENTRY)) {
            dispatchBaseStatistics(settings);
            return;
        }

        items = new Gson().fromJson(
                settings.getString(Utils.GENRE_STATS_ENTRY, ""),
                Utils.ARRAY_GENRE_ITEMS);

        for (GenreItem item : items) {
            item.setLastPlayed("");
            item.setPlayCount(0);
            item.setSingers(new ArrayList<String>());
        }

        SharedPreferences.Editor editor = settings.edit();
        editor.putString(
                Utils.GENRE_STATS_ENTRY,
                new Gson().toJson(items));

        editor.apply();

        if (mAdapter == null) {
            mAdapter = new GenresListAdapter(items, this, getActivity());
        } else {
            mAdapter.notifyDataSetChanged();
        }

        Toast.makeText(getActivity(), "Cleared", Toast.LENGTH_SHORT).show();
    }

    /**
     * Populates the entry with the base genres and usage statistics
     * @param settings Shared Preferences
     */
    public void dispatchBaseStatistics(SharedPreferences settings) {
        items = new ArrayList<>();
        for (String genre : Utils.genres) {
            GenreItem newItem = new GenreItem();
            newItem.setGenreValue(genre);
            items.add(newItem);
        }

        SharedPreferences.Editor editor = settings.edit();
        editor.putString(Utils.GENRE_STATS_ENTRY, new Gson().toJson(items));
        editor.apply();

        if (mAdapter == null) {
            mAdapter = new GenresListAdapter(items, this, getActivity());
        } else {
            mAdapter.notifyDataSetChanged();
        }

        Toast.makeText(getActivity(), "Successfully created entries", Toast.LENGTH_SHORT).show();
        return;
    }

    @Override
    public void onSuccess(ArrayList<TrackLookupResponse> result) {
        playQueue = result;

        Uri builtUri = Uri.parse(result.get(0).getStream_url()).buildUpon()
                .appendQueryParameter("client_id", Utils.clientKey)
                .build();

        // Build the ExoPlayer and start playback
        releasePlayer();
        attatchPlayer(builtUri);
    }

    private void attatchPlayer(Uri builtUri) {

        // Build the sample source
        FrameworkSampleSource sampleSource = new FrameworkSampleSource(getActivity(), builtUri, null, 1);

        // Build the track renderers
        TrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource, null, true);

        exoPlayer = ExoPlayer.Factory.newInstance(1);
        exoPlayer.addListener(this);
        exoPlayer.prepare(audioRenderer);
        exoPlayer.setPlayWhenReady(true);

        playerStatusListener.onFeedbackAvailable(items.get(isPlaying).getGenreValue());
    }

    private void releasePlayer() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
        }
    }

    @Override
    public void onFailure(Exception error) {
        Log.e("Failure", error.toString());
    }

    @Override
    public void onProgressUpdate(ProgressUpdateItem progress) {

    }


    //---------- Player related calls ----------------------------------------------------------------------
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        switch (playbackState) {
            case ExoPlayer.STATE_BUFFERING:
                playerStatusListener.onFeedbackAvailable("Buffering");
                break;

            case ExoPlayer.STATE_PREPARING:
                playerStatusListener.onFeedbackAvailable("Preparing");
                break;

            case ExoPlayer.STATE_READY:
                playerStatusListener.onFeedbackAvailable("Playing " + items.get(isPlaying).getGenreValue());
                playerStatusListener.onPlayerStart();
                break;

            case ExoPlayer.STATE_ENDED:
                playerStatusListener.onFeedbackAvailable("Finished");
                break;

            case ExoPlayer.STATE_IDLE:
                break;
        }
    }

    @Override
    public void onPlayWhenReadyCommitted() {
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        Toast.makeText(getActivity(), error.toString(), Toast.LENGTH_LONG).show();
        Log.e("PlayerError", error.toString());
        playerStatusListener.onFeedbackAvailable("Error");
        releasePlayer();
    }

    @Override
    public void onPause() {

        super.onPause();
    }

    @Override
    public void onAttach(Activity activity) {
        try {
            playerStatusListener = (MainActivity) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnPlayerStatusChanged");
        }
        super.onAttach(activity);
    }

    public void onFabButtonTap() {
        playerStatusListener.onFeedbackAvailable("MESSAGE ABAILABLE OH BITCHA");
    }
}