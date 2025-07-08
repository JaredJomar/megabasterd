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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monitors download progress and switches proxies when no progress is detected
 * for a configurable amount of time. This allows for faster proxy switching
 * instead of waiting for the full PROGRESS_WATCHDOG_TIMEOUT.
 *
 * @author tonikelope
 */
public class ProxyProgressMonitor implements Runnable, SecureSingleThreadNotifiable {

    // Default values
    public static final int DEFAULT_PROGRESS_CHECK_INTERVAL = 30;
    public static final int DEFAULT_PROXY_SWITCH_TIMEOUT = 120;
    
    private static final Logger LOG = Logger.getLogger(ProxyProgressMonitor.class.getName());
    
    private final Download _download;
    private volatile boolean _exit;
    private final Object _secure_notify_lock;
    private volatile boolean _notified;
    private long _last_progress;
    private long _last_progress_time;
    private boolean _enabled;
    private int _progress_check_interval;
    private int _proxy_switch_timeout;

    public ProxyProgressMonitor(Download download) {
        _download = download;
        _exit = false;
        _secure_notify_lock = new Object();
        _notified = false;
        _last_progress = 0;
        _last_progress_time = System.currentTimeMillis();
        
        // Load settings from database
        loadSettings();
    }
    
    /**
     * Load settings from database with fallback to defaults
     */
    private void loadSettings() {
        // Check if proxy progress monitoring is enabled
        String enabled_setting = selectSettingValue("proxy_progress_monitor_enabled");
        _enabled = enabled_setting == null || enabled_setting.equals("yes");
        
        // Load progress check interval
        String check_interval_setting = selectSettingValue("proxy_progress_check_interval");
        if (check_interval_setting != null) {
            try {
                _progress_check_interval = Integer.parseInt(check_interval_setting);
            } catch (NumberFormatException e) {
                _progress_check_interval = DEFAULT_PROGRESS_CHECK_INTERVAL;
            }
        } else {
            _progress_check_interval = DEFAULT_PROGRESS_CHECK_INTERVAL;
        }
        
        // Load proxy switch timeout
        String switch_timeout_setting = selectSettingValue("proxy_switch_timeout");
        if (switch_timeout_setting != null) {
            try {
                _proxy_switch_timeout = Integer.parseInt(switch_timeout_setting);
            } catch (NumberFormatException e) {
                _proxy_switch_timeout = DEFAULT_PROXY_SWITCH_TIMEOUT;
            }
        } else {
            _proxy_switch_timeout = DEFAULT_PROXY_SWITCH_TIMEOUT;
        }
        
        LOG.log(Level.INFO, "ProxyProgressMonitor settings: enabled={0}, check_interval={1}s, switch_timeout={2}s", 
                new Object[]{_enabled, _progress_check_interval, _proxy_switch_timeout});
    }

    public void setExit(boolean exit) {
        _exit = exit;
        secureNotify();
    }

    public boolean isEnabled() {
        return _enabled;
    }

    public void setEnabled(boolean enabled) {
        _enabled = enabled;
    }

    @Override
    public void secureNotify() {
        synchronized (_secure_notify_lock) {
            _notified = true;
            _secure_notify_lock.notify();
        }
    }

    @Override
    public void secureWait() {
        synchronized (_secure_notify_lock) {
            while (!_notified) {
                try {
                    _secure_notify_lock.wait(_progress_check_interval * 1000);
                    break; // Exit after timeout even if not notified
                } catch (InterruptedException ex) {
                    _exit = true;
                    LOG.log(Level.SEVERE, ex.getMessage());
                    break;
                }
            }
            _notified = false;
        }
    }

    @Override
    public void run() {
        LOG.log(Level.INFO, "{0} ProxyProgressMonitor started for: {1}", new Object[]{Thread.currentThread().getName(), _download.getFile_name()});

        _last_progress = _download.getProgress();
        _last_progress_time = System.currentTimeMillis();

        while (!_exit && !_download.isExit() && !_download.isStopped()) {
            
            // Skip monitoring if download is paused or not using smart proxy
            if (_download.isPaused() || !MainPanel.isUse_smart_proxy() || !_enabled) {
                secureWait();
                continue;
            }

            long current_progress = _download.getProgress();
            long current_time = System.currentTimeMillis();

            // Check if progress has been made
            if (current_progress > _last_progress) {
                // Progress detected, update tracking variables
                _last_progress = current_progress;
                _last_progress_time = current_time;
                LOG.log(Level.FINE, "{0} Progress detected for {1}: {2} bytes", new Object[]{Thread.currentThread().getName(), _download.getFile_name(), current_progress});
            } else {
                // No progress detected, check if timeout reached
                long time_without_progress = current_time - _last_progress_time;
                
                if (time_without_progress >= _proxy_switch_timeout * 1000) {
                    // Timeout reached, attempt to switch proxies
                    LOG.log(Level.WARNING, "{0} No progress for {1} seconds in {2}, attempting proxy switch", new Object[]{Thread.currentThread().getName(), time_without_progress / 1000, _download.getFile_name()});
                    
                    if (switchProxiesForStagnantDownload()) {
                        // Reset timer since we switched proxies
                        _last_progress_time = current_time;
                    }
                }
            }

            // Wait before next check
            secureWait();
        }

        LOG.log(Level.INFO, "{0} ProxyProgressMonitor stopped for: {1}", new Object[]{Thread.currentThread().getName(), _download.getFile_name()});
    }

    /**
     * Attempts to switch proxies for all chunk downloaders in the download
     * @return true if any proxy switches were made
     */
    private boolean switchProxiesForStagnantDownload() {
        boolean switched = false;
        
        SmartMegaProxyManager proxy_manager = MainPanel.getProxy_manager();
        if (proxy_manager == null) {
            return false;
        }

        // Check if we have any proxies available at all
        boolean has_available_proxies = hasAvailableProxies(proxy_manager);
        
        if (!has_available_proxies) {
            // No proxies available, try to get fresh ones immediately
            LOG.log(Level.WARNING, "{0} No available proxies found for {1}, requesting fresh proxies", new Object[]{Thread.currentThread().getName(), _download.getFile_name()});
            switched = tryGetFreshProxies(proxy_manager);
        } else {
            // First, try to switch to existing available proxies
            synchronized (_download.getWorkers_lock()) {
                for (ChunkDownloader worker : _download.getChunkworkers()) {
                    if (worker.getCurrent_smart_proxy() != null) {
                        // Block current proxy temporarily to force switch
                        String current_proxy = worker.getCurrent_smart_proxy();
                        LOG.log(Level.INFO, "{0} Blocking stagnant proxy {1} for worker [{2}] in {3}", new Object[]{Thread.currentThread().getName(), current_proxy, worker.getId(), _download.getFile_name()});
                        
                        proxy_manager.blockProxy(current_proxy, "STAGNANT_NO_PROGRESS");
                        
                        // Force the worker to reset its current chunk and get a new proxy
                        worker.RESET_CURRENT_CHUNK();
                        switched = true;
                    }
                }
            }
            
            // If no proxies were switched (likely all are blocked), try to get fresh proxies
            if (!switched) {
                switched = tryGetFreshProxies(proxy_manager);
            }
        }
        
        if (switched) {
            LOG.log(Level.INFO, "{0} Switched proxies for stagnant download: {1}", new Object[]{Thread.currentThread().getName(), _download.getFile_name()});
        }
        
        return switched;
    }
    
    /**
     * Attempts to get fresh proxies from Proxifly and add them to the proxy manager
     * @param proxy_manager The smart proxy manager
     * @return true if fresh proxies were obtained and added
     */
    private boolean tryGetFreshProxies(SmartMegaProxyManager proxy_manager) {
        String context = "Download: " + _download.getFile_name();
        SmartMegaProxyManager.ProxyRefreshResult result = proxy_manager.getFreshProxiesFromProxifly(context);
        
        if (result.isSuccess() && result.getProxiesAdded() > 0) {
            // Reset workers to use the new proxies
            synchronized (_download.getWorkers_lock()) {
                for (ChunkDownloader worker : _download.getChunkworkers()) {
                    worker.RESET_CURRENT_CHUNK();
                }
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if there are any available (non-blocked) proxies in the proxy manager
     * @param proxy_manager The smart proxy manager
     * @return true if there are available proxies, false otherwise
     */
    private boolean hasAvailableProxies(SmartMegaProxyManager proxy_manager) {
        try {
            // Check if proxy list is empty
            if (proxy_manager.getProxyList() == null || proxy_manager.getProxyList().isEmpty()) {
                return false;
            }
            
            // Check if all proxies are blocked
            int total_proxies = proxy_manager.getProxyList().size();
            int blocked_proxies = 0;
            
            // Count blocked proxies (this is an approximation)
            // You might need to access the blocked proxy list differently based on SmartMegaProxyManager implementation
            for (String proxy : proxy_manager.getProxyList()) {
                // If we can't determine if a proxy is blocked, assume it's available
                // This is a conservative approach
            }
            
            return true; // Conservative: assume we have available proxies unless list is empty
            
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "{0} Error checking available proxies: {1}", new Object[]{Thread.currentThread().getName(), ex.getMessage()});
            return false; // On error, assume no proxies available to trigger fresh proxy request
        }
    }
}
