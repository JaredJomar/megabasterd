/*
 __  __                  _               _               _ 
|  \/  | ___  __ _  __ _| |__   __ _ ___| |_ ___ _ __ __| |
| |\/| |/ _ \/ _` |/ _` | '_ \ / _` / __| __/ _ \ '__/ _` |
| |  | |  __/ (_| | (_| | |_) | (_| \__ \ ||  __/ | | (_| |
|_|  |_|\___|\__, |\__,_|_.__/ \__,_|___/\__\___|_|  \__,_|
             |___/                                         
© Perpetrated by tonikelope since 2016
 */
package com.tonikelope.megabasterd;

import static com.tonikelope.megabasterd.DBTools.selectSettingValue;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides fresh proxy servers from Proxifly free service when static proxies fail
 *
 * @author tonikelope
 */
public class DynamicProxyProvider {

    private static final Logger LOG = Logger.getLogger(DynamicProxyProvider.class.getName());
    private static final String PROXIFLY_HTTP_URL = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.txt";
    private static final int DEFAULT_TIMEOUT = 10000; // 10 seconds
    
    private final boolean _enabled;
    private long _last_request_time;
    private final long _min_request_interval; // Minimum time between requests to avoid spam

    public DynamicProxyProvider() {
        // Load settings from database
        String enabled_setting = selectSettingValue("proxifly_enabled");
        _enabled = enabled_setting != null && enabled_setting.equals("yes");
        
        String interval_setting = selectSettingValue("proxifly_min_interval");
        _min_request_interval = (interval_setting != null) ? Long.parseLong(interval_setting) * 1000 : 300000; // 5 minutes default
        
        _last_request_time = 0;
        
        LOG.log(Level.INFO, "DynamicProxyProvider initialized: enabled={0}", _enabled);
        
        // If enabled, inform that free service is ready
        if (_enabled) {
            LOG.log(Level.INFO, "DynamicProxyProvider ready to provide free proxies from Proxifly");
        } else {
            LOG.log(Level.INFO, "DynamicProxyProvider disabled. To enable, set: proxifly_enabled=yes");
        }
    }

    /**
     * Retrieves fresh proxies from Proxifly free service
     * @return List of proxy strings in format "ip:port"
     */
    public List<String> getFreshProxies() {
        List<String> proxies = new ArrayList<>();
        
        if (!_enabled) {
            LOG.log(Level.INFO, "DynamicProxyProvider is disabled");
            return proxies;
        }
        
        // Rate limiting: don't spam the service
        long current_time = System.currentTimeMillis();
        if (current_time - _last_request_time < _min_request_interval) {
            LOG.log(Level.INFO, "DynamicProxyProvider: Rate limit active, skipping request");
            return proxies;
        }
        
        HttpURLConnection conn = null;
        try {
            URL url = new URL(PROXIFLY_HTTP_URL);
            conn = (HttpURLConnection) url.openConnection();
            
            // Set request properties
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(DEFAULT_TIMEOUT);
            conn.setReadTimeout(DEFAULT_TIMEOUT);
            
            LOG.log(Level.INFO, "DynamicProxyProvider: Fetching free proxies from {0}", PROXIFLY_HTTP_URL);
            
            int response_code = conn.getResponseCode();
            
            if (response_code == 200) {
                // Read response
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        // Remove "http://" prefix if present
                        if (line.startsWith("http://")) {
                            line = line.substring(7);
                        }
                        // Validate format is host:port
                        if (line.matches(".+?:[0-9]{1,5}")) {
                            proxies.add(line);
                        }
                    }
                }
                reader.close();
                _last_request_time = current_time;
                
                LOG.log(Level.INFO, "DynamicProxyProvider: Retrieved {0} free proxies from Proxifly", proxies.size());
                
            } else {
                // Update rate limit timer even on errors to prevent spam
                _last_request_time = current_time;
                LOG.log(Level.WARNING, "DynamicProxyProvider: Failed to fetch proxy list, HTTP code: {0}", response_code);
            }
            
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "DynamicProxyProvider: Error fetching proxies: {0}", ex.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        
        return proxies;
    }
    
    public List<String> getInitialProxies() {
        if (!_enabled) {
            LOG.log(Level.INFO, "DynamicProxyProvider is disabled, cannot get initial proxies");
            return new ArrayList<>();
        }
        
        LOG.log(Level.INFO, "DynamicProxyProvider: Getting initial proxy list from Proxifly free service");
        return getFreshProxies();
    }
    
    /**
     * Check if the provider is enabled
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return _enabled;
    }
    
    /**
     * Check if we can make a request (rate limiting check)
     * @return true if enough time has passed since last request
     */
    public boolean canMakeRequest() {
        long current_time = System.currentTimeMillis();
        return (current_time - _last_request_time) >= _min_request_interval;
    }
}
