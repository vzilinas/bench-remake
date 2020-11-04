/**
 * Copyright 2015, Yahoo Inc.
 * Licensed under the terms of the Apache License 2.0. Please see LICENSE file in the project root for terms.
 */
package org.vz.common.advertising;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


public class CampaignProcessorCommon {
    private static final Logger LOG = LoggerFactory.getLogger(CampaignProcessorCommon.class);
    private Jedis flush_jedis;
    // Bucket -> Campaign_id -> Window
    private LRUHashMap<Long, HashMap<String, Window>> campaign_windows;
    private Set<CampaignWindowPair> need_flush;


    private Long time_divisor; // 10 second windows

    public CampaignProcessorCommon(String redisServerHostname, Long time_divisor) {

        flush_jedis = new Jedis(redisServerHostname);
        this.time_divisor = time_divisor;
    }

    public void prepare() {

        campaign_windows = new LRUHashMap<Long, HashMap<String, Window>>(10);
        need_flush = new HashSet<CampaignWindowPair>();

        Runnable flusher = new Runnable() {
            public void run() {
                try {
                    while (true) {
                        Thread.sleep(1000);
                        flushWindows();
                    }
                } catch (InterruptedException e) {
                    LOG.error("Interrupted", e);
                }
            }
        };
        new Thread(flusher).start();
    }

    public void execute(String campaign_id, String event_time) {
        Long timeBucket = Long.parseLong(event_time) / time_divisor;
        Window window = getWindow(timeBucket, campaign_id);
        window.seenCount++;

        CampaignWindowPair newPair = new CampaignWindowPair(campaign_id, window);
        synchronized(need_flush) {
            need_flush.add(newPair);
        }
    }

    private void writeWindow(String campaign, Window win) {
        String windowUUID = flush_jedis.hmget(campaign, win.timestamp).get(0);
        if (windowUUID == null) {
            windowUUID = UUID.randomUUID().toString();
            flush_jedis.hset(campaign, win.timestamp, windowUUID);

            String windowListUUID = flush_jedis.hmget(campaign, "windows").get(0);
            if (windowListUUID == null) {
                windowListUUID = UUID.randomUUID().toString();
                flush_jedis.hset(campaign, "windows", windowListUUID);
            }
            flush_jedis.lpush(windowListUUID, win.timestamp);
        }

        synchronized (campaign_windows) {
            flush_jedis.hincrBy(windowUUID, "seen_count", win.seenCount);
            win.seenCount = 0L;
        }
        flush_jedis.hset(windowUUID, "time_updated", Long.toString(System.currentTimeMillis()));
        flush_jedis.lpush("time_updated", Long.toString(System.currentTimeMillis()));
    }

    private void flushWindows() {
        synchronized (need_flush) {
            for (CampaignWindowPair pair : need_flush) {
                writeWindow(pair.campaign, pair.window);
            }
            need_flush.clear();
        }
    }

    public Window redisGetWindow(Long timeBucket, Long time_divisor) {

        Window win = new Window();
        win.timestamp = Long.toString(timeBucket * time_divisor);
        win.seenCount = 0L;
        return win;
    }

    // Needs to be rewritten now that redisGetWindow has been simplified.
    // This can be greatly simplified.
    private Window getWindow(Long timeBucket, String campaign_id) {
        synchronized (campaign_windows) {
            HashMap<String, Window> bucket_map = campaign_windows.get(timeBucket);
            if (bucket_map == null) {
                // Try to pull from redis into cache.
                Window redisWindow = redisGetWindow(timeBucket, time_divisor);
                if (redisWindow != null) {
                    bucket_map = new HashMap<String, Window>();
                    campaign_windows.put(timeBucket, bucket_map);
                    bucket_map.put(campaign_id, redisWindow);
                    return redisWindow;
                }

                // Otherwise, if nothing in redis:
                bucket_map = new HashMap<String, Window>();
                campaign_windows.put(timeBucket, bucket_map);
            }

            // Bucket exists. Check the window.
            Window window = bucket_map.get(campaign_id);
            if (window == null) {
                // Try to pull from redis into cache.
                Window redisWindow = redisGetWindow(timeBucket, time_divisor);
                if (redisWindow != null) {
                    bucket_map.put(campaign_id, redisWindow);
                    return redisWindow;
                }

                // Otherwise, if nothing in redis:
                window = new Window();
                window.timestamp = Long.toString(timeBucket * time_divisor);
                window.seenCount = 0L;
                bucket_map.put(campaign_id, null);
            }
            return window;
        }
    }



    public static void main(String[] args){
        CampaignProcessorCommon campaignProcessorCommon = new CampaignProcessorCommon("redis", 10000L);
        campaignProcessorCommon.prepare();
        campaignProcessorCommon.execute("1", "1522620341534");
        campaignProcessorCommon.execute("1", "1522620344534");
        LOG.info(campaignProcessorCommon.getWindow(152262034L, "1").toString());
        campaignProcessorCommon.flushWindows();
        LOG.info(campaignProcessorCommon.getWindow(152262034L, "1").toString());
        campaignProcessorCommon.execute("1", "1522620346534");
        LOG.info(campaignProcessorCommon.getWindow(152262034L, "1").toString());

    }
}
