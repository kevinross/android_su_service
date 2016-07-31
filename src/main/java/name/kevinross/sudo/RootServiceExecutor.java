package name.kevinross.sudo;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import name.kevinross.tool.AbstractTool;
import name.kevinross.tool.ReflectionUtil;

/**
 * Created by kevinross (contact@kevinross.name) on 2016-07-14.
 */
public class RootServiceExecutor extends AbstractTool {
    private int hostUid = -1;
    private boolean allowOthers = false;
    private boolean keepAlive = false;
    private Binder token = null;
    public RootServiceExecutor() {

    }

    @Override
    public String getAppName() {
        return String.format("service:%s", parsedArgs.valueOf("s").toString());
    }
    @Override
    protected OptionParser getArgParser() {
        return new OptionParser("p:s:");
    }
    private Class<Binder> getStubClass(Class<IInterface> iface) {
        return ReflectionUtil.getInnerClass(iface, "Stub");
    }
    private void setDescriptor(Class c, String val) {
        ReflectionUtil.invokes().on(getStubClass(c)).name("DESCRIPTOR").using(val).nosy().swallow().set();
    }
    private String getDescriptor(Class c) {
        return ReflectionUtil.invokes().on(getStubClass(c)).name("DESCRIPTOR").nosy().swallow().get();
    }
    private Context getPackageContext(String pkg) {
        try {
            return getActivityThread().getApplication().createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }
    private void registerControl(String pkg, Class<IInterface> parent) {
        setDescriptor(IRemoteService.class, getDescriptor(parent) + ".Control");
        hostUid = getPackageContext(pkg).getApplicationInfo().uid;
        IRemoteService mRemote = new IRemoteService.Stub() {
            @Override
            public void bindToken(IBinder token) throws RemoteException {
                if (!allowOthers && Binder.getCallingUid() != hostUid) {
                    throw new RuntimeException("Caller not from proper package");
                }
                // when the host app goes, we might go with it
                RootServiceExecutor.this.token = (Binder) token;
                RootServiceExecutor.this.token.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        if (keepAlive) {
                            return;
                        }
                        System.exit(0);
                    }
                }, 0);
            }

            @Override
            public void killService() throws RemoteException {
                System.exit(0);
            }
        };
        ServiceManager.addService(getDescriptor(IRemoteService.class), mRemote.asBinder());
    }
    private <B extends IBinder> B getAuthenticatingImplementation(ClassLoader cl, Class<IInterface> iface, final B parent) {
        return (B) Proxy.newProxyInstance(cl, new Class[]{IBinder.class, iface}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (Binder.getCallingUid() != hostUid) {
                    throw new RemoteException(String.format("Not called by app in package: Calling=%d, Package=%d", Binder.getCallingUid(), hostUid));
                }
                return method.invoke(parent, args);
                //return ReflectionUtil.invokes().on(parent).name(method.getName()).of(ReflectionUtil.paramsToTypes(args)).using(args).swallow().invoke();
            }
        });
    }
    @Override
    protected void run(OptionSet parser) {
        // get our context
        Context pkgContext = getPackageContext(parser.valueOf("p").toString());
        // get our classloader so we only load the right classes
        ClassLoader apkClassLoader = pkgContext.getClassLoader();
        // the package to pull from
        // the class to use as the service
        String serviceName = parser.valueOf("s").toString();
        Class<RootService> serviceClass = ReflectionUtil.getClassByName(apkClassLoader, serviceName);
        Object service = ReflectionUtil.invokes().on(serviceClass).of(String.class).using("").swallow().getNewInstance();
        // permissions
        allowOthers = ReflectionUtil.invokes().on(service).name("allowOthers").nosy().swallow().invoke();
        // lifecycle
        keepAlive = ReflectionUtil.invokes().on(service).name("keepAlive").nosy().swallow().invoke();
        // the interface to provide
        Class<IInterface> interfaceClass = ReflectionUtil.invokes().on(service).name("getInterfaceClass").swallow().invoke();
        Binder binimpl = ReflectionUtil.invokes().on(service).name("getImplementation").swallow().invoke();
        registerControl(parser.valueOf("p").toString(), interfaceClass);

        // register the service and use the implementation provided by the RootService subclass
        if (allowOthers) {
            ServiceManager.addService(interfaceClass.getName(), binimpl);
        } else {
            final IBinder authimpl = getAuthenticatingImplementation(apkClassLoader, interfaceClass, binimpl);
            ServiceManager.addService(interfaceClass.getName(), new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    return authimpl.transact(code, data, reply, flags);
                }
            });
        }

        // spin until Control#killService()
        try {
            getActivityThread().getLooper().getThread().join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }
}
