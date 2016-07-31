// IRemoteInterface.aidl
package name.kevinross.sudo;
import android.os.IBinder;
import android.os.Parcelable;

// Declare any non-default types here with import statements

interface IRemoteService {
    void bindToken(IBinder token);
    void killService();
}
