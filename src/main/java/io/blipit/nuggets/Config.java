package io.blipit.nuggets;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Config {
    public interface Hook {
        void onEvent(Map<String, Object> event, boolean error);
    }

    public String key;
    public String endpoint = "https://in.blipit.io";
    public String release;
    public String environment;
    public String sdkName = "blipit-java";
    public String sdkVersion = "0";
    public String platform = "java";
    public File dir;
    public boolean sendCrashNow = true;
    public int maxQueued = 100;
    public String[] notInApp = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "scala.",
        "android.", "androidx.", "com.android.", "dalvik.", "libcore.", "com.google.android.",
        "org.junit.", "org.gradle.", "io.blipit."
    };
    public final Map<String, Object> contexts = new LinkedHashMap<String, Object>();
    public Hook hook;

    public String base() {
        String e = endpoint == null || endpoint.isEmpty() ? "https://in.blipit.io" : endpoint;
        while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
        return e;
    }
}
