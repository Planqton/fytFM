package android.qf.util;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.KeyEvent;

/**
 * FYT System class for key event information.
 * This is a stub that matches the system class signature.
 */
public class QFKeyEventInfo implements Parcelable {
    private int mKeyCode;
    private KeyEvent mKeyEvent;

    public static final Creator<QFKeyEventInfo> CREATOR = new Creator<QFKeyEventInfo>() {
        @Override
        public QFKeyEventInfo createFromParcel(Parcel in) {
            QFKeyEventInfo info = new QFKeyEventInfo();
            info.readFromParcel(in, 0);
            return info;
        }

        @Override
        public QFKeyEventInfo[] newArray(int size) {
            return new QFKeyEventInfo[size];
        }
    };

    public QFKeyEventInfo() {
        mKeyEvent = null;
        mKeyCode = 0;
    }

    public QFKeyEventInfo(QFKeyEventInfo other) {
        mKeyEvent = other.mKeyEvent;
        mKeyCode = other.mKeyCode;
    }

    public int getKeyCode() {
        return mKeyCode;
    }

    public void setKeyCode(int keyCode) {
        mKeyCode = keyCode;
    }

    public KeyEvent getKeyEventInfo() {
        return mKeyEvent;
    }

    public void setKeyEventInfo(KeyEvent keyEvent) {
        mKeyEvent = keyEvent;
    }

    public void readFromParcel(Parcel parcel, int flags) {
        mKeyCode = parcel.readInt();
        mKeyEvent = parcel.readParcelable(KeyEvent.class.getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mKeyCode);
        dest.writeParcelable(mKeyEvent, 1);
    }
}
