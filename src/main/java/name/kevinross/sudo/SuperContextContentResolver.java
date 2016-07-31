package name.kevinross.sudo;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.ApplicationThreadNative;
import android.app.IActivityManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;

import java.util.List;

import name.kevinross.tool.ReflectionUtil;

/**
 * Get a content resolver for a given context. Here there be monsters.
 */
public class SuperContextContentResolver {
    public static List<ProviderInfo> getContentProviders(Context context, String appName) {
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(appName, 0);
            //noinspection WrongConstant
            return context.getPackageManager().queryContentProviders(appInfo.processName, appInfo.uid, PackageManager.GET_SHARED_LIBRARY_FILES | PackageManager.GET_URI_PERMISSION_PATTERNS);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }
    public static void injectProviders(Context context, ActivityThread thread) {
        injectProviders(context, thread, "system");
    }
    public static void injectProviders(Context context, ActivityThread thread, String... providers) {
        for (String s : providers) {
            for (ProviderInfo info : getContentProviders(context, s)) {
                injectContentProviderIntoApplication(ClassLoader.getSystemClassLoader(), thread, context, info.authority);
            }
        }
    }
    public static void injectContentProviderIntoApplication(ClassLoader classLoader, ActivityThread activityThread, Context context, String name) {
        IActivityManager.ContentProviderHolder holder = null;
        IContentProvider provider = null;
        IBinder token = new Binder();

        try {
            holder = ActivityManagerNative.getDefault().getContentProviderExternal(name, context.getUserId(), token);
            provider = holder.provider;
        } catch (RemoteException e) {
            throw new RuntimeException("Couldn't get content provider for given name and context+userid");
        }

        /*
        class ApplicationContentResolver {
            public ApplicationContentResolver(Context, ActivityThread, UserHandle);
        }
        context.mContentResolver = new ApplicationContentResolver(context, ActivityThread.currentActivityThread(), UserHandle.CURRENT);
         */
        Class applicationContentResolverClass = ReflectionUtil.getInnerClass(ReflectionUtil.getClassByName(classLoader, "android.app.ContextImpl"), "ApplicationContentResolver");
        ContentResolver resolver = ReflectionUtil.invokes().on(applicationContentResolverClass).
                of(Context.class, ActivityThread.class, UserHandle.class).
                using(context, ActivityThread.currentActivityThread(), UserHandle.CURRENT).nosy().swallow().<ContentResolver>getNewInstance();
        ReflectionUtil.invokes().on(context).name("mContentResolver").using(resolver).nosy().swallow().set();

        /**
         class ProviderKey {
            public ProviderKey(String authority, int userid);
         }
         class ProviderClientRecord {
            public ProviderClientRecord(ActivityThread thread, String[] names,
                                        IContentProvider provider,
                                        ContentProvider localProvider,
                                        IActivityManager.ContentProviderHolder holder);
         }
         ProviderKey providerKey = new ProviderKey("settings", -2) // -2 is "magic value"
         ProviderClientRecord providerRecord = new ProviderClientRecord(activityThread,
                                                    new String[]{name}, provider, null, holder);
         */
        Class providerKeyClass = ReflectionUtil.getInnerClass(ActivityThread.class, "ProviderKey");
        Object providerKey = ReflectionUtil.invokes().on(providerKeyClass).of(String.class, Integer.TYPE).using("settings", -2).nosy().swallow().getNewInstance();

        Class providerRecordClass = ReflectionUtil.getInnerClass(ActivityThread.class, "ProviderClientRecord");
        Object providerRecord = ReflectionUtil.invokes().on(providerRecordClass).
                of(ActivityThread.class, new String[]{}.getClass(), IContentProvider.class,
                        ContentProvider.class,
                        IActivityManager.ContentProviderHolder.class).
                using(activityThread, new String[]{name}, provider, null, holder).nosy().swallow().getNewInstance();

        /*
             ArrayMap mProviderMap = activityThread.mProviderMap;
         */
        ArrayMap mProviderMap = ReflectionUtil.invokes().on(activityThread).name("mProviderMap").nosy().swallow().<ArrayMap>get();
        synchronized (mProviderMap) {
            if (!mProviderMap.containsKey(providerKey)) {
                mProviderMap.put(providerKey, providerRecord);
            }
        }
    }
    private static void disposeContentProvider() {
        //ActivityManagerNative.getDefault().removeContentProviderExternal("settings", token);
    }
}
