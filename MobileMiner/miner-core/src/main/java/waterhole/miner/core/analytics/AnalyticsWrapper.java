package waterhole.miner.core.analytics;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import waterhole.miner.core.BuildConfig;
import waterhole.miner.core.utils.HttpRequest;

import static waterhole.miner.core.asyn.AsyncTaskAssistant.executeOnThreadPool;
import static waterhole.miner.core.utils.LogUtils.error;
import static waterhole.miner.core.utils.LogUtils.info;
import static android.provider.Settings.System.ANDROID_ID;

/**
 * 统计包装类.
 *
 * @author kzw on 2018/06/05.
 */
public final class AnalyticsWrapper {

    private static final int DEF_VALUE = -1;
    private static final String BASE_API = "http://192.168.1.185:8080/";
    private static final String SAVE_BASE_INFO_API = BASE_API + "save_base_info";
    private static final String INIT_API = BASE_API + "init";
    private static final String STORE_ERROR = BASE_API + "store_err";
    private static final String MINING = BASE_API + "mining";
    public static final String GET_CONFIG = BASE_API + "get_config";

    public AnalyticsWrapper() {
        throw new RuntimeException("AnalyticsWrapper stub!");
    }

    public static void initApplication(final Application application) {
        if (application == null) return;
        if (getDeviceID(application) == DEF_VALUE) {
            executeOnThreadPool(new Runnable() {
                @Override
                public void run() {
                    try {
                        final AnalyticsDevice device = new AnalyticsDevice();
                        device.deviceName = Build.MODEL;
                        device.deviceVersion = Build.VERSION.RELEASE;
                        device.androidId = Settings.System.getString(application.getContentResolver(), ANDROID_ID);
                        device.abi = Build.CPU_ABI;
                        device.cpuCoreThreads = Runtime.getRuntime().availableProcessors();
                        Map<String, Object> map = new HashMap<>();
                        map.put("device_name", device.deviceName);
                        map.put("device_version", device.deviceVersion);
                        map.put("android_id", device.androidId);
                        map.put("abi", device.abi);
                        map.put("cpu_core_threads", device.cpuCoreThreads);
                        // todo kzw 数据做加密处理
                        String response = HttpRequest.post(SAVE_BASE_INFO_API).send(fromMapToJson(map)).body();
                        info("onDeviceEvent response = " + response);
                        int deviceId = optJsonIntAttr(response, "device_id");
                        if (deviceId != DEF_VALUE) {
                            cacheDeviceID(application, deviceId);
                            onInitEvent(application, deviceId);
                        }
                    } catch (HttpRequest.HttpRequestException e) {
                        error(e.getMessage());
                    }
                }
            });
        } else {
            onInitEvent(application, getDeviceID(application));
        }
    }

    private static void onInitEvent(final Application application, final int deviceId) {
        if (application != null && deviceId != DEF_VALUE) {
            executeOnThreadPool(new Runnable() {
                @Override
                public void run() {
                    try {
                        final AnalyticsInit init = new AnalyticsInit();
                        init.deviceId = deviceId;
                        init.sdkVersion = BuildConfig.VERSION_NAME;
                        init.packageName = application.getPackageName();
                        init.appName = getAppName(application);
                        init.appVersion = getAppVersionName(application);
                        init.startTime = System.currentTimeMillis();
                        Map<String, Object> map = new HashMap<>();
                        map.put("device_id", init.deviceId);
                        map.put("sdk_version", init.sdkVersion);
                        map.put("app_package_name", init.packageName);
                        map.put("app_name", init.appName);
                        map.put("app_version", init.appVersion);
                        map.put("start_time", init.startTime);
                        // todo kzw 数据做加密处理
                        String response = HttpRequest.post(INIT_API).send(fromMapToJson(map)).body();
                        info("onInitEvent response = " + response);
                        int mineId = optJsonIntAttr(response, "mine_id");
                        if (mineId != DEF_VALUE) {
                            cacheMineID(application, mineId);
                        }
                    } catch (HttpRequest.HttpRequestException e) {
                        error(e.getMessage());
                    }
                }
            });
        }
    }

    public static void onMiningEvent(final Context context, final double speed) {
        if (context == null) return;
        executeOnThreadPool(new Runnable() {
            @Override
            public void run() {
                try {
                    final AnalyticsMining mining = new AnalyticsMining();
                    mining.mineId = getMineID(context);
                    mining.coin = getMineCoin(context);
                    mining.cpuUseThreads = getCacheCpuUseThreads(context);
                    mining.cpuUses = getCacheCpuUse(context);
                    mining.scene = getMineScene(context);
                    mining.temperature = getCpuTemperature(context);
                    mining.speed = speed;
                    mining.miningTime = System.currentTimeMillis();
                    Map<String, Object> map = new HashMap<>();
                    map.put("mine_id", mining.mineId);
                    map.put("coin", mining.coin);
                    map.put("use_threads", mining.cpuUseThreads);
                    map.put("cpu_uses", mining.cpuUses);
                    map.put("scene", mining.scene);
                    map.put("speed", mining.speed);
                    map.put("temperature", mining.temperature);
                    map.put("mining_time", mining.miningTime);
                    // todo kzw 数据做加密处理
                    int code = HttpRequest.post(MINING).send(fromMapToJson(map)).code();
                    info("onMiningEvent code = " + code);
                } catch (HttpRequest.HttpRequestException e) {
                    error(e.getMessage());
                }
            }
        });
    }

    public static void onErrorEvent(final Context context, final String error) {
        if (context == null || TextUtils.isEmpty(error)) return;
        executeOnThreadPool(new Runnable() {
            @Override
            public void run() {
                try {
                    int deviceId = getDeviceID(context);
                    if (deviceId == DEF_VALUE) {
                        return;
                    }
                    Map<String, Object> map = new HashMap<>();
                    map.put("device_id", deviceId);
                    map.put("error", error);
                    // todo kzw 数据做加密处理
                    int code = HttpRequest.post(STORE_ERROR).send(fromMapToJson(map)).code();
                    info("onError code = " + code);
                } catch (HttpRequest.HttpRequestException e) {
                    error(e.getMessage());
                }
            }
        });
    }

    private static void cacheDeviceID(final Context context, final int _deviceID) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putInt("WATERHOLE_CORE_DEVICE_ID", _deviceID).apply();
    }

    private static int getDeviceID(final Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getInt("WATERHOLE_CORE_DEVICE_ID", DEF_VALUE);
    }

    private static void cacheMineID(final Context context, final int _deviceID) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putInt("WATERHOLE_CORE_MINE_ID", _deviceID).apply();
    }

    private static int getMineID(final Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getInt("WATERHOLE_CORE_MINE_ID", DEF_VALUE);
    }

    public static void cacheMineCoin(final Context context, final String coin) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putString("WATERHOLE_CORE_MINE_COIN", coin).apply();
    }

    private static String getMineCoin(final Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getString("WATERHOLE_CORE_MINE_COIN", "");
    }

    public static void cacheCpuUseThreads(final Context context, final int cpuUseThreads) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putInt("WATERHOLE_CORE_MINE_CPU_USE_THREADS", cpuUseThreads).apply();
    }

    private static int getCacheCpuUseThreads(final Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getInt("WATERHOLE_CORE_MINE_CPU_USE_THREADS", DEF_VALUE);
    }

    public static void cacheCpuUse(final Context context, final int cpuUse) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putInt("WATERHOLE_CORE_MINE_CPU_USE", cpuUse).apply();
    }

    private static int getCacheCpuUse(final Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getInt("WATERHOLE_CORE_MINE_CPU_USE", DEF_VALUE);
    }

    public static void cacheMineScene(final Context context, final String scene) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putString("WATERHOLE_CORE_MINING_SCENE", scene).apply();
    }

    private static String getMineScene(final Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getString("WATERHOLE_CORE_MINING_SCENE", "");
    }

    public static void cacheCpuTemperature(final Context context, final double temperature) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putFloat("WATERHOLE_CORE_CPU_TEMPERATURE", (float) temperature).apply();
    }

    private static float getCpuTemperature(final Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getFloat("WATERHOLE_CORE_CPU_TEMPERATURE", DEF_VALUE);
    }

    /**
     * 获取应用程序名称
     */
    private static String getAppName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
            int labelRes = packageInfo.applicationInfo.labelRes;
            return context.getResources().getString(labelRes);
        } catch (PackageManager.NameNotFoundException e) {
            error(e.getMessage());
        }
        return null;
    }

    /**
     * [获取应用程序版本名称信息]
     *
     * @return 当前应用的版本名称
     */
    private static String getAppVersionName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    context.getPackageName(), 0);
            return packageInfo.versionName;

        } catch (PackageManager.NameNotFoundException e) {
            error(e.getMessage());
        }
        return null;
    }

    private static int optJsonIntAttr(String response, String key) {
        if (!TextUtils.isEmpty(response) && !TextUtils.isEmpty(key)) {
            try {
                JSONObject jsonObject = new JSONObject(response);
                int code = jsonObject.optInt("code");
                if (code == 0) {
                    return jsonObject.optInt(key, DEF_VALUE);
                }
            } catch (JSONException e) {
                error(e.getMessage());
            }
        }
        return DEF_VALUE;
    }

    private static String fromMapToJson(Map<String, Object> map) {
        final List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        final StringBuilder builder = new StringBuilder();
        builder.append("{");
        int size = keys.size();
        for (int index = 0; index < size; index++) {
            String key = keys.get(index);
            Object value = map.get(key);
            builder.append("\"").append(key).append("\":");
            if (value instanceof String) {
                builder.append("\"").append(value).append("\"");
            } else {
                builder.append(value);
            }
            if (index != size - 1) {
                builder.append(",");
            }
        }
        builder.append("}");
        return builder.toString();
    }
}