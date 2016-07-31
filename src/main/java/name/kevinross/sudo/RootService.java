package name.kevinross.sudo;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import name.kevinross.tool.ReflectionUtil;

/**
 * Running Services in a root context
 *
 * Helper tools are okay and all but they have their limitations. Plus, if you have to run the same
 * tool over and over again, there are performance and UI-blocking implications. Wouldn't it be great
 * to use the tool like just another part of your app instead of as a shell script? Wouldn't it also
 * be great to not have to run it multiple times for different things? Maybe you'd like it to be a
 * service so you can call on it as needed? Answers: yes, yes, and yes (at least for me).
 *
 * RootService spawns your service as another user and connects your app to that process through Binder,
 * presenting your service to your app as if it were a regular service, proxying calls through to the
 * spawned process. Additionally, it rejects calls (by default) from outside your package so other
 * actors on the system can't use your service, only you can. Finally, the service process is attached
 * to the service lifecycle: it gets spawned on #onBind and killed on #onUnbind (by default). If your
 * app gets killed by the system, the service process goes with it (again, by default).
 *
 * Howto:
 *
 * 1) Create your AIDL as usual
 * 2) Add your service to the manifest
 * 3) Create a new class that subclasses this one instead of Service, using the proper generic parameters
 *      I -> Your interface class (IYourInterface)
 *      B -> the "Stub" inner class inside your interface class (IYourInterface.Stub)
 * 4) Implement the abstract methods and constructors
 *      RootService(String param)
 *          Constructor used in the service process to get to the service implementation. If you want to
 *          do something in the service process outside of the service, do it here. You only have
 *          access to Java's standard library plus anything *not* requiring app framework stuff (Contexts,
 *          Content Resolvers, Activities, etc).
 *      #getImplementation()
 *          As in normal services, return "new IYourInterface.Stub() {}" implementing the methods
 *          of your interface. This code will run in a root context and not have access to the state
 *          in the app's process unless you explicitly pass it in later using methods in your interface
 *      #getInterfaceClass()
 *          RootService requires the class object for your interface so the only thing this method
 *          needs to do is return it. This is an artifact of the limitations of generics.
 *
 *      Optionally override:
 *      #getUid()
 *          Normally, this returns 0 to run as root but if you want to run as another user, perhaps
 *          1000, override and return the desired uid.
 *      #allowOthers()
 *          Normally, the service process rejects calls by apps from other packages, return true here
 *          to allow those calls.
 *      #keepAlive()
 *          Normally, the service process follows the app's lifecycle: spawned on #onBind, killed on
 *          #onUnbind, and if the app gets killed the service goes with it. Return true here to ignore
 *          those events.
 *
 *      You're allowed to override #onBind and #onUnbind if and only if you call super.onWhatever too.
 *      These methods take care of starting and stopping the service process so if you don't call through
 *      to the implementation here, your service won't spawn.
 *
 *
 * 5) Use the service literally as you normally would if it extended from Service:
 *          ServiceConnection serviceConnection = new ServiceConnection() {
 *              @Override
 *              public void onServiceConnected(ComponentName name, IBinder service) {
 *                  IYourInterface myInterface = IYourInterface.Stub.asInterface(service);
 *                  myInterface.hello();
 *              }
 *          }
 *          bindService(new Intent(this, YourServiceClass.class), serviceConnection, flags);
 *
 */
public abstract class RootService<I extends IInterface, B extends Binder> extends Service {
    /**
     * Service implementation
     * @return
     */
    protected abstract B getImplementation();

    /**
     * Service interface
     * @return
     */
    protected abstract Class<I> getInterfaceClass();

    /**
     * Uid of user to run as
     * @return
     */
    protected int getUid() {
        return uid;
    }

    /**
     * Allow other apps to access the service
     * @return
     */
    protected boolean allowOthers() {
        return false;
    }

    /**
     * Permit the service to keep running outside of the app and the app's lifecycle
     * @return
     */
    protected boolean keepAlive() {
        return false;
    }
    private int uid = 0;
    private I iface = null;
    private Class<I> ifacecls = null;
    private I serverimpl = null;
    private IRemoteService servercontrol = null;
    private Class<B> implcls = null;
    private Binder binder = null;
    private Thread serviceThread = null;

    /**
     * App-side constructor used by the framework to instantiate the Service.
     */
    public RootService() {
        super();
        this.ifacecls = getInterfaceClass();
        this.implcls = ReflectionUtil.getInnerClass(ifacecls, "Stub");
        this.iface = getInterface();
        String descriptor = ReflectionUtil.invokes().on(implcls).name("DESCRIPTOR").nosy().swallow().get();
        this.binder = new Binder();
        this.binder.attachInterface(iface, descriptor);
    }

    /**
     * {@inheritDoc}
     *
     * Start the service process and bind to the advertised service
     *
     * @param intent
     * @return
     */
    public IBinder onBind(Intent intent) {
        int i = 0;
        IBinder b = ServiceManager.getService(this.getInterfaceClass().getName() + ".Control");
        if (b != null) {
            servercontrol = IRemoteService.Stub.asInterface(b);
        }
        if (servercontrol == null) {
            RootServiceExecutor exec = new RootServiceExecutor();
            serviceThread = exec.runService(0, this.getApplication().getApplicationContext(), "-p", this.getPackageName(), "-s", this.getClass().getName());
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {}
        }
        while (servercontrol == null && i < 8) {
            b = ServiceManager.getService(this.getInterfaceClass().getName() + ".Control");
            if (b != null) {
                servercontrol = IRemoteService.Stub.asInterface(b);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {}
            i += 1;
        }
        if (servercontrol == null) {
            return null;
        }
        try {
            servercontrol.bindToken(this.binder);
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
        IBinder b2 = ServiceManager.getService(this.getInterfaceClass().getName());
        if (b2 != null) {
            serverimpl = ReflectionUtil.invokes().on(implcls).name("asInterface").of(IBinder.class).using(b2).swallow().invoke();
        }
        return binder;
    }

    /**
     * {@inheritDoc}
     *
     * Take down the service process if configured to do so
     * @param intent
     * @return
     */
    @Override
    public boolean onUnbind(Intent intent) {
        if (keepAlive()) {
            return false;
        }
        try {
            servercontrol.killService();
            serverimpl = null;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Proxy that passes calls through to the service process
     * @return
     */
    private I getInterface() {
        return (I)Proxy.newProxyInstance(ifacecls.getClassLoader(), new Class[]{ifacecls}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("asBinder")) {
                    return binder;
                }
                return ReflectionUtil.invokes().on(serverimpl).name(method.getName()).of(ReflectionUtil.paramsToTypes(args)).using(args).swallow().invoke();
            }
        });
    }
}
