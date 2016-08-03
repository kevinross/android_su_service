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
 * presenting your service to your app as if it were a regular service running as the user assigned to
 * your app, proxying calls through to the spawned process. Additionally, it rejects calls (by default)
 * from outside your package so other actors on the system can't use your service, only you can. Finally,
 * the service process is attached to the service lifecycle: it gets spawned on #onBind and killed on
 * #onUnbind (by default). If your app gets killed by the system, the service process goes with
 * it (again, by default).
 *
 * Howto:
 *
 * 1) Create your AIDL as usual
 * 2) Add your service to the manifest
 * 3) Create a new class that subclasses this one instead of Service, using the proper generic parameter
 *      B -> the "Stub" inner class inside your interface class (IYourInterface.Stub)
 * 4) Implement the #onBind method:
 *      Normally, you return an implementation of IYourInterface.Stub instance here. This time,
 *      return super.onBind(intent, stubInstanceHere). super.onBind starts the service process and passing
 *      the stub instance to it provides the service-process-side implementation. Nothing else *needs*
 *      to be done at this point however there are some options available to modify the behaviour of
 *      the service: uid to run as, permitting other apps to use the service, and keeping the service
 *      alive. #onUnbind can also be overridden however be sure to call super.onUnbind at the very
 *      end.
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
 *
 * 5) Use the service literally as you normally would if it extended from Service:
 *          IYourInterface myInterface = null;
 *          ServiceConnection serviceConnection = new ServiceConnection() {
 *              @Override
 *              public void onServiceConnected(ComponentName name, IBinder service) {
 *                  myInterface = IYourInterface.Stub.asInterface(service);
 *              }
 *          }
 *          bindService(new Intent(this, YourServiceClass.class), serviceConnection, flags);
 *          myInterface.hello("world");
 *
 */
public abstract class RootService<B extends Binder> extends Service {
    public static String RUNNING_IN_ROOT = "running_in_root";

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
    private IRemoteService servercontrol = null;
    private Binder binder = null;
    private Thread serviceThread = null;

    /**
     * App-side constructor used by the framework to instantiate the Service.
     */
    public RootService() {
        super();
        this.binder = new Binder();
    }

    public IRemoteService getControl() {
        return this.servercontrol;
    }

    /**
     * {@inheritDoc}
     *
     * Start the service process and bind to the advertised service
     *
     * @param intent
     * @return
     */
    public IBinder onBind(Intent intent, B impl) {
        if (intent.hasExtra(RUNNING_IN_ROOT)) {
            return impl;
        }
        String ifacecls = impl.getClass().getSuperclass().getInterfaces()[0].getName();
        String controliface = ifacecls + ".Control";
        int i = 0;
        IBinder b = ServiceManager.getService(controliface);
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
        while (servercontrol == null && i < 8) {    // try for 2 seconds in intervals of 250m
            // s
            b = ServiceManager.getService(controliface);
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
        return ServiceManager.getService(ifacecls);
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
        } catch (RemoteException | NullPointerException e) {
            e.printStackTrace();
        }
        return false;
    }
}
